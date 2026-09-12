package com.dazix.caelis.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.dazix.caelis.R
import com.dazix.caelis.data.horizon.HorizonState

/**
 * Progress in the shade while terrain elevation is fetched and the skyline computed.
 *
 * Worth a notification because this is the one thing in the app that takes tens of megabytes and
 * tens of seconds: the AR screen is where you look at the sky, not at a spinner, and the phone is
 * usually going into a pocket while the tiles come down.
 *
 * Posted through `NotificationManager`, like the session notification and for the same reason — no
 * foreground service exists to attach it to.
 *
 * Silent and in status bar (`IMPORTANCE_DEFAULT` with sound = null, enableVibration = false). Ongoing while active
 * (`Downloading`, `Computing`), so it stays visible in the status bar and cannot be accidentally swiped away mid-progress.
 */
class TerrainNotifications(context: Context) {

    private val appContext: Context = context.applicationContext
    private val manager: NotificationManager =
        appContext.getSystemService(NotificationManager::class.java)

    private var lastPostUptime = 0L
    private var visible = false
    private var lastStateClass: Class<out HorizonState>? = null

    /**
     * Reflects [state] in the shade, or clears the notification when there is nothing in flight.
     *
     * Progress arrives far faster than a notification should be re-posted — the download callback
     * fires per 64 kB chunk — so updates are throttled. Phase transitions (e.g. from Downloading to
     * Computing) and terminal states are never throttled: a notification stuck at 97% because the last
     * update was dropped is worse than no notification.
     */
    fun update(state: HorizonState) {
        val stateClass = state::class.java
        val isPhaseChange = lastStateClass != stateClass
        lastStateClass = stateClass

        when (state) {
            is HorizonState.Downloading -> {
                val pct = (state.progress * PROGRESS_MAX).toInt()
                post(
                    title = appContext.getString(R.string.horizon_notif_downloading, state.index, state.total),
                    text = "${state.key.name} · $pct %",
                    progress = pct,
                    indeterminate = false,
                    throttled = !isPhaseChange,
                    ongoing = true,
                    autoCancel = false,
                )
            }

            is HorizonState.Computing -> {
                val pct = (state.progress * PROGRESS_MAX).toInt()
                post(
                    title = appContext.getString(R.string.horizon_notif_computing),
                    text = "$pct %",
                    progress = pct,
                    indeterminate = false,
                    throttled = !isPhaseChange,
                    ongoing = true,
                    autoCancel = false,
                )
            }

            is HorizonState.Ready -> {
                if (!visible) return
                post(
                    title = appContext.getString(R.string.horizon_notif_ready),
                    text = appContext.getString(
                        R.string.horizon_notif_ready_detail,
                        state.profile.maxElevationDegrees.toInt(),
                    ),
                    progress = null,
                    indeterminate = false,
                    throttled = false,
                    ongoing = false,
                    autoCancel = true,
                )
            }

            is HorizonState.Failed -> {
                if (!visible) return
                post(
                    title = appContext.getString(R.string.horizon_notif_failed),
                    text = appContext.getString(reasonText(state.reason)),
                    progress = null,
                    indeterminate = false,
                    throttled = false,
                    ongoing = false,
                    autoCancel = true,
                )
            }

            // Nothing is happening, or the app is waiting on the user to import a file. Neither is
            // progress, and a notification that cannot be acted on from the shade is just noise.
            is HorizonState.Idle, is HorizonState.NeedsTiles -> cancel()
        }
    }

    fun cancel() {
        if (!visible) return
        visible = false
        lastPostUptime = 0L
        lastStateClass = null
        manager.cancel(NOTIFICATION_ID)
    }

    private fun post(
        title: String,
        text: String?,
        progress: Int?,
        indeterminate: Boolean,
        throttled: Boolean,
        ongoing: Boolean = false,
        autoCancel: Boolean = false,
    ) {
        val now = SystemClock.uptimeMillis()
        if (throttled && visible && now - lastPostUptime < MIN_POST_INTERVAL_MS) return
        lastPostUptime = now

        ensureChannel()

        val builder = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(autoCancel)

        contentIntent()?.let { builder.setContentIntent(it) }

        text?.let { builder.setContentText(it) }
        progress?.let { builder.setProgress(PROGRESS_MAX, it.coerceIn(0, PROGRESS_MAX), indeterminate) }

        try {
            manager.notify(NOTIFICATION_ID, builder.build())
            visible = true
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted. The download is not worth failing over a notification
            // the user declined, and the AR screen shows the same state inline.
            visible = false
        }
    }

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

    private fun reasonText(reason: HorizonState.Failed.Reason): Int = when (reason) {
        HorizonState.Failed.Reason.NETWORK -> R.string.horizon_status_failed_network
        HorizonState.Failed.Reason.METERED -> R.string.horizon_status_failed_metered
        HorizonState.Failed.Reason.STORAGE -> R.string.horizon_status_failed_network
        HorizonState.Failed.Reason.NO_DATA -> R.string.horizon_status_failed_no_data
    }

    private fun ensureChannel() {
        // Remove legacy channel with IMPORTANCE_LOW from earlier versions if present
        runCatching { manager.deleteNotificationChannel(OLD_CHANNEL_ID) }

        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.horizon_notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = appContext.getString(R.string.horizon_notif_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        /** Distinct from [SessionNotifications.NOTIFICATION_ID]: a download must not replace a run. */
        const val NOTIFICATION_ID = 0x1DEB
        const val CHANNEL_ID = "terrain_progress"
        private const val OLD_CHANNEL_ID = "terrain_download"

        private const val REQUEST_CONTENT = 201
        private const val PROGRESS_MAX = 100
        private const val MIN_POST_INTERVAL_MS = 400L
    }
}
