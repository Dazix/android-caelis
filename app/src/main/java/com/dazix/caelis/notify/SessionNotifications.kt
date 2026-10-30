package com.dazix.caelis.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.dazix.caelis.R
import com.dazix.caelis.data.SessionRepository
import com.dazix.caelis.domain.SessionSnapshot
import com.dazix.caelis.domain.SessionState
import java.util.Locale

/**
 * The ongoing, low-importance session notification: shots taken, countdown to the next shot, any
 * warning, plus Pause/Resume and Stop actions.
 *
 * Call order the service must respect:
 *  1. [attachTo] once, from `onCreate`/session start — registers the action receiver and posts the
 *     first notification.
 *  2. [update] as often as it likes; posts are throttled to at most one per second, and the last
 *     snapshot handed in always reaches the shade (trailing post).
 *  3. [clear] on session end / `onDestroy` — cancels the notification and unregisters the receiver.
 *
 * While the state is time-dependent ([SessionState.WaitingToStart], [SessionState.Running]) the
 * notification re-posts itself once a second without being asked to: the countdown is computed from
 * the clock, so a shade that is only refreshed when the snapshot changes shows a frozen "next in"
 * until the next shot lands. The re-posting stops as soon as the state stops counting down, so a
 * paused or finished session costs nothing.
 *
 * Set [onPauseResume] / [onStop] before [attachTo] if the service wants the taps delivered directly.
 * When they are null the action is re-broadcast as [ACTION_PAUSE_RESUME] / [ACTION_STOP] inside this
 * package instead, so a component that registers for those still hears it.
 *
 * ### Why this is not a foreground service notification
 * [attachTo] used to call `startForeground` on the accessibility service. That could never work: an
 * [android.accessibilityservice.AccessibilityService] has no `foregroundServiceType`, and from
 * targetSdk 34 onwards `startForeground` on a service without one throws
 * `MissingForegroundServiceTypeException`. The call was wrapped in `runCatching`, so every session
 * ever run took the "fall back to a plain post" branch — what shipped and what was measured on the
 * device was already a plain notification. It is now the only path, which is why the shade behaves
 * identically.
 *
 * The alternative — a separate `specialUse` foreground service owning this notification — was
 * rejected:
 *  * It buys no process protection. The accessibility service is bound by `system_server` with
 *    `BIND_AUTO_CREATE or BIND_FOREGROUND_SERVICE`, which already pins the process at foreground-service
 *    priority and has the platform restart and re-bind it if it is ever killed. A foreground service of
 *    our own would sit at the same adj.
 *  * It introduces a crash this app cannot afford. A session can be started from a context that is not
 *    in the foreground (the notification's own Resume action, the debug broadcast receiver), and
 *    `startForegroundService` from the background throws `ForegroundServiceStartNotAllowedException`;
 *    so does failing to call `startForeground` within the grace period. Trading a swallowed exception
 *    for a real one is not a fix.
 *  * Surviving a process death is a persistence problem, and it is solved where it belongs — in
 *    `service.RunStateStore`, which lets an alarm rebuild the run.
 *
 * The consequence to be aware of: an ongoing notification that does not belong to a foreground service
 * can be dismissed by the user. The once-a-second re-post while a session counts down brings it
 * straight back, and the overlay is the second surface, so a session never becomes invisible.
 */
class SessionNotifications(context: Context) {

    private val appContext: Context = context.applicationContext
    private val manager: NotificationManager =
        appContext.getSystemService(NotificationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** Invoked when the user taps the Pause/Resume action. The service decides which one it means. */
    var onPauseResume: (() -> Unit)? = null

    /** Invoked when the user taps the Stop action. */
    var onStop: (() -> Unit)? = null

    private var service: Service? = null
    private var receiver: CommandReceiver? = null

    /** Seeded from the repository so attaching mid-session does not flash "0 shots". */
    private var latest: SessionSnapshot = SessionRepository.snapshot.value
    private var lastPostUptime = 0L
    private var tickScheduled = false

    /** Posting happens from any thread; a duplicate warning is harmless, a missing one is not. */
    @Volatile
    private var blockedLogged = false

    /**
     * Posts the notification on behalf of [service] and starts listening for its action taps.
     * Idempotent.
     *
     * [service] is kept only as the owner token that gates posting (see [post]); the notification is
     * posted through [NotificationManager], not attached to a foreground service. See the class KDoc
     * for why.
     */
    fun attachTo(service: Service) {
        synchronized(lock) {
            this.service = service
            // Re-read rather than trust the construction-time seed: attaching happens on the first
            // non-idle snapshot, and posting the seed would flash "idle" for a session that is
            // already counting down.
            latest = SessionRepository.snapshot.value
            ensureChannel()
            registerReceiver()
        }
        post(build(latest))
        lastPostUptime = SystemClock.uptimeMillis()
        if (countsDown(latest.state)) scheduleTick()
    }

    /**
     * Refreshes the shade text. Safe from any thread; at most one post per second, and a countdown
     * state leaves the once-a-second re-post armed behind it.
     */
    fun update(snapshot: SessionSnapshot) {
        val postNow: Boolean
        synchronized(lock) {
            latest = snapshot
            val waited = SystemClock.uptimeMillis() - lastPostUptime
            postNow = waited >= THROTTLE_MS && !tickScheduled
            if (postNow) lastPostUptime = SystemClock.uptimeMillis()
        }
        if (postNow) post(build(snapshot))
        // Not posted yet -> a trailing tick delivers this snapshot; counting down -> keep ticking.
        if (!postNow || countsDown(snapshot.state)) scheduleTick()
    }

    /** Cancels the notification and unregisters the action receiver. */
    fun clear() {
        synchronized(lock) {
            main.removeCallbacks(tick)
            tickScheduled = false
            unregisterReceiver()
            service = null
        }
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    // ------------------------------------------------------------------------------- internals

    /**
     * The one scheduled post. It carries whatever snapshot is current when it runs, so it doubles as
     * the trailing post of a coalesced burst and as the countdown's own clock.
     */
    private val tick = Runnable {
        val snapshot: SessionSnapshot
        val attached: Boolean
        synchronized(lock) {
            tickScheduled = false
            lastPostUptime = SystemClock.uptimeMillis()
            snapshot = latest
            attached = service != null
        }
        post(build(snapshot))
        if (attached && countsDown(snapshot.state)) scheduleTick()
    }

    /** Arms the single pending post. A tick that is already scheduled will carry [latest] anyway. */
    private fun scheduleTick() {
        synchronized(lock) {
            if (tickScheduled) return
            tickScheduled = true
            val waited = SystemClock.uptimeMillis() - lastPostUptime
            main.postDelayed(tick, (THROTTLE_MS - waited).coerceIn(0L, THROTTLE_MS))
        }
    }

    /** True while the text depends on the clock and therefore has to be recomputed every second. */
    private fun countsDown(state: SessionState): Boolean =
        state is SessionState.Running || state is SessionState.WaitingToStart

    /**
     * Nothing reaches the shade before [attachTo] or after [clear]: an idle snapshot arrives as soon
     * as the service connects, and a notification for a session no component owns would sit there
     * forever. It also closes the race where a tick already running on the main thread would re-post
     * what [clear] just cancelled.
     */
    private fun post(notification: Notification) {
        if (synchronized(lock) { service } == null) return
        warnOnceIfBlocked()
        // Never worth crashing a session over; still says so out loud, because a session whose
        // notification never reaches the shade is invisible to the user.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "session notification was rejected by the framework", it) }
    }

    /**
     * `notify()` does not throw when the app may not post: the notification is enqueued and dropped
     * (`numEnqueuedByApp` grows, `numPostedByApp` stays 0), which is exactly what an ungranted
     * POST_NOTIFICATIONS looks like. Logged once so the invisible countdown is diagnosable.
     */
    private fun warnOnceIfBlocked() {
        if (blockedLogged) return
        val enabled = runCatching { manager.areNotificationsEnabled() }.getOrDefault(true)
        if (enabled) return
        blockedLogged = true
        Log.w(
            TAG,
            "notifications are blocked for this app (POST_NOTIFICATIONS not granted, or the " +
                "channel is off): the session notification is enqueued and never posted, so the " +
                "user sees no shot count and no countdown",
        )
    }

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.ovl_notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = appContext.getString(R.string.ovl_notif_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun registerReceiver() {
        if (receiver != null) return
        val created = CommandReceiver(this)
        val filter = IntentFilter().apply {
            addAction(internalAction(ACTION_SUFFIX_PAUSE_RESUME))
            addAction(internalAction(ACTION_SUFFIX_STOP))
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext, created, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess { receiver = created }
    }

    private fun unregisterReceiver() {
        val current = receiver ?: return
        receiver = null
        runCatching { appContext.unregisterReceiver(current) }
    }

    internal fun dispatch(action: String) {
        when (action) {
            internalAction(ACTION_SUFFIX_PAUSE_RESUME) -> {
                val handler = onPauseResume
                if (handler != null) handler() else rebroadcast(ACTION_PAUSE_RESUME)
            }

            internalAction(ACTION_SUFFIX_STOP) -> {
                val handler = onStop
                if (handler != null) handler() else rebroadcast(ACTION_STOP)
            }
        }
    }

    private fun rebroadcast(action: String) {
        runCatching {
            appContext.sendBroadcast(
                Intent(action).setPackage(appContext.packageName)
            )
        }
    }

    private fun internalAction(suffix: String) = appContext.packageName + suffix

    private fun actionIntent(suffix: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            requestCode,
            Intent(internalAction(suffix)).setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Launch intent rather than a class literal, so this file stays decoupled from the UI module. */
    private fun contentIntent(): PendingIntent? {
        val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            appContext,
            REQUEST_CONTENT,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun build(snapshot: SessionSnapshot): Notification {
        val state = snapshot.state
        val live = state is SessionState.Running ||
            state is SessionState.Paused ||
            state is SessionState.WaitingToStart

        val builder = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appContext.getString(titleFor(state)))
            .setContentText(contentTextFor(snapshot))
            .setOngoing(live)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        contentIntent()?.let { builder.setContentIntent(it) }

        val warning = snapshot.warning
        if (!warning.isNullOrBlank()) {
            builder.setSubText(warning)
            builder.setStyle(
                Notification.BigTextStyle().bigText(contentTextFor(snapshot) + "\n" + warning)
            )
        }

        if (live) {
            val paused = state is SessionState.Paused
            builder.addAction(
                Notification.Action.Builder(
                    platformIcon(
                        if (paused) android.R.drawable.ic_media_play
                        else android.R.drawable.ic_media_pause
                    ),
                    appContext.getString(
                        if (paused) R.string.ovl_action_resume else R.string.ovl_action_pause
                    ),
                    actionIntent(ACTION_SUFFIX_PAUSE_RESUME, REQUEST_PAUSE_RESUME),
                ).build()
            )
            builder.addAction(
                Notification.Action.Builder(
                    platformIcon(android.R.drawable.ic_menu_close_clear_cancel),
                    appContext.getString(R.string.ovl_action_stop),
                    actionIntent(ACTION_SUFFIX_STOP, REQUEST_STOP),
                ).build()
            )
        }

        val planned = snapshot.shotsPlanned
        if (live && planned != null && planned > 0) {
            builder.setProgress(planned, snapshot.shotsTaken.coerceIn(0, planned), false)
        }

        return builder.build()
    }

    private fun titleFor(state: SessionState): Int = when (state) {
        is SessionState.Running -> R.string.ovl_notif_title_running
        is SessionState.Paused -> R.string.ovl_notif_title_paused
        is SessionState.WaitingToStart -> R.string.ovl_notif_title_waiting
        is SessionState.Stopped -> R.string.ovl_notif_title_stopped
        is SessionState.Idle -> R.string.ovl_notif_title_idle
    }

    private fun contentTextFor(snapshot: SessionSnapshot): String {
        val planned = snapshot.shotsPlanned
        val shots = if (planned != null && planned > 0) {
            appContext.resources.getQuantityString(
                R.plurals.ovl_shots_of, planned, snapshot.shotsTaken, planned,
            )
        } else {
            appContext.resources.getQuantityString(
                R.plurals.ovl_shots, snapshot.shotsTaken, snapshot.shotsTaken,
            )
        }
        val now = SystemClock.elapsedRealtime()
        val tail = when (val state = snapshot.state) {
            is SessionState.Paused -> appContext.getString(R.string.ovl_paused)
            is SessionState.Idle -> appContext.getString(R.string.ovl_idle)
            is SessionState.Stopped -> appContext.getString(R.string.ovl_stopped)
            is SessionState.WaitingToStart -> appContext.getString(
                R.string.ovl_starts_in,
                formatCountdown(state.startsAtElapsed - now),
            )

            is SessionState.Running -> {
                val next = snapshot.nextShotAtElapsed
                if (next == null || next - now <= 0L) {
                    appContext.getString(R.string.ovl_shooting_now)
                } else {
                    appContext.getString(R.string.ovl_next_in, formatCountdown(next - now))
                }
            }
        }
        return "$shots · $tail"
    }

    /** Platform drawables for notification actions (play, pause, cancel). */
    private fun platformIcon(resId: Int): Icon = Icon.createWithResource("android", resId)

    companion object {
        private const val TAG = "SessionNotifications"

        const val NOTIFICATION_ID = 0x1DEA
        const val CHANNEL_ID = "session_progress"

        /** Broadcast in-package when [onPauseResume] is not set. */
        const val ACTION_PAUSE_RESUME = "com.dazix.caelis.notify.PAUSE_RESUME"

        /** Broadcast in-package when [onStop] is not set. */
        const val ACTION_STOP = "com.dazix.caelis.notify.STOP"

        private const val ACTION_SUFFIX_PAUSE_RESUME = ".notify.internal.PAUSE_RESUME"
        private const val ACTION_SUFFIX_STOP = ".notify.internal.STOP"

        private const val REQUEST_PAUSE_RESUME = 101
        private const val REQUEST_STOP = 102
        private const val REQUEST_CONTENT = 103

        private const val THROTTLE_MS = 1_000L

        /** mm:ss, or h:mm:ss past an hour. Rounds up, so 1400 ms reads "0:02" not "0:01". */
        private fun formatCountdown(remainingMs: Long): String {
            val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            val locale = Locale.getDefault()
            return if (hours > 0L) {
                String.format(locale, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(locale, "%d:%02d", minutes, seconds)
            }
        }
    }
}

/**
 * Runtime-registered only — no manifest entry, so the actions cannot be fired by anything outside
 * this process while a session is not attached.
 */
private class CommandReceiver(private val owner: SessionNotifications) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        owner.dispatch(action)
    }
}
