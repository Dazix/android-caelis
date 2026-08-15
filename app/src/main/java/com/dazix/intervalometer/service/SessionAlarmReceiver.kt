package com.dazix.intervalometer.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wakes the accessibility service for the next shot when the interval is long enough that a `Handler`
 * callback would be frozen by Doze. Only used for gaps at or above
 * [SessionEngine.HANDLER_GAP_LIMIT_MS]; short gaps stay on the main-looper handler.
 *
 * ### When the service is not there yet
 * A long gap is exactly when the process is most likely to be reclaimed: nothing is running, no wake
 * lock is held. The alarm then *restarts* the process, and this receiver runs before the platform has
 * re-bound the accessibility service — so a missing [ShutterAccessibilityService.instance] usually
 * means "not back yet", not "the user switched it off".
 *
 * Declaring the run lost right there ended perfectly healthy sessions. Instead: if the service is
 * still enabled in Settings and [RunStateStore] holds a resumable run, the broadcast is kept alive
 * with `goAsync` and the instance is polled for up to [REBIND_WAIT_MS]. The engine restores itself
 * from [RunStateStore] as it connects, and the shot lands late but lands. Only when the service really
 * is gone — disabled, or never coming back — is the run recorded as
 * [com.dazix.intervalometer.domain.StopReason.SERVICE_LOST], which is what used to happen every time.
 */
class SessionAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SHOT_DUE) return
        val shotIndex = intent.getIntExtra(EXTRA_SHOT_INDEX, -1)

        val service = ShutterAccessibilityService.instance()
        if (service != null) {
            service.onSessionAlarm(shotIndex)
            return
        }

        val appContext = context.applicationContext
        if (!isServiceEnabled(appContext) || !RunStateStore(appContext).isResumable()) {
            Log.w(TAG, "shot $shotIndex due but the accessibility service is not connected")
            SessionEngine.recordServiceLost(appContext)
            return
        }

        Log.i(TAG, "shot $shotIndex due while the service is rebinding, waiting up to ${REBIND_WAIT_MS}ms")
        awaitRebind(appContext, shotIndex)
    }

    /**
     * Holds the broadcast open while the platform brings the accessibility service back.
     *
     * Polling on the main looper rather than sleeping a thread: the [PendingResult] is what keeps the
     * process and its wake lock alive, and the deadline stays under the ten seconds a receiver is
     * allowed so the wait can never turn into an ANR.
     */
    private fun awaitRebind(context: Context, shotIndex: Int) {
        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
        val deadline = SystemClock.elapsedRealtime() + REBIND_WAIT_MS
        val finished = AtomicBoolean(false)

        fun finish(action: () -> Unit) {
            if (!finished.compareAndSet(false, true)) return
            runCatching { action() }
            runCatching { pending.finish() }
        }

        val poll = object : Runnable {
            override fun run() {
                val service = ShutterAccessibilityService.instance()
                if (service != null) {
                    Log.i(TAG, "service came back, delivering shot $shotIndex")
                    finish { service.onSessionAlarm(shotIndex) }
                    return
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    Log.w(TAG, "service did not come back for shot $shotIndex, run is lost")
                    finish { SessionEngine.recordServiceLost(context) }
                    return
                }
                handler.postDelayed(this, REBIND_POLL_MS)
            }
        }
        handler.post(poll)
    }

    /**
     * Whether the user still has this app's accessibility service switched on. A service that is
     * enabled but not currently bound is the rebinding case worth waiting for; one that is not enabled
     * is never coming back on its own.
     */
    private fun isServiceEnabled(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return true
        val wanted = ComponentName(context, ShutterAccessibilityService::class.java)
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.matches(wanted) }
    }.getOrElse {
        // Cannot tell: assume it is still enabled and let the wait decide. Being wrong costs a few
        // seconds; being wrong the other way ends a live session.
        true
    }

    /**
     * `resolveInfo` is the exact answer; `id` is the flattened component name and covers the case
     * where the platform hands back an entry without one.
     */
    private fun AccessibilityServiceInfo.matches(wanted: ComponentName): Boolean {
        val info = resolveInfo?.serviceInfo
        if (info != null) return info.packageName == wanted.packageName && info.name == wanted.className
        return id == wanted.flattenToString() || id == wanted.flattenToShortString()
    }

    companion object {
        private const val TAG = "SessionAlarmReceiver"

        const val ACTION_SHOT_DUE = "com.dazix.intervalometer.action.SHOT_DUE"
        const val EXTRA_SHOT_INDEX = "com.dazix.intervalometer.extra.SHOT_INDEX"

        /** One request code: a new alarm replaces the previous one and [cancel] always matches it. */
        private const val REQUEST_CODE = 0x5A0

        /** Comfortably inside the ten seconds a foreground broadcast receiver is given. */
        private const val REBIND_WAIT_MS = 8_000L
        private const val REBIND_POLL_MS = 200L

        private fun baseIntent(context: Context): Intent =
            Intent(context, SessionAlarmReceiver::class.java).setAction(ACTION_SHOT_DUE)

        fun pendingIntent(context: Context, shotIndex: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                baseIntent(context).putExtra(EXTRA_SHOT_INDEX, shotIndex),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /**
         * Cancels the pending shot alarm, including one left behind by a previous process — pending
         * intents live in the system, so `FLAG_NO_CREATE` finds it after a restart. Extras are not part
         * of a [PendingIntent]'s identity, so looking it up without them still matches, and nothing is
         * created just to be thrown away when there is no alarm to cancel.
         */
        fun cancel(context: Context) {
            val pending = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                baseIntent(context),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: return
            runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(pending) }
            pending.cancel()
        }
    }
}
