package com.dazix.caelis.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dazix.caelis.data.horizon.HorizonState
import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerrainNotificationsTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var notifications: TerrainNotifications

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        notifications = TerrainNotifications(context)
    }

    @Test
    fun `channel is created with IMPORTANCE_DEFAULT, silent and no vibration`() {
        val key = DemTileKey(50, 14)
        notifications.update(HorizonState.Downloading(key, 1, 3, 0.45f))

        val channel = manager.getNotificationChannel(TerrainNotifications.CHANNEL_ID)
        assertNotNull("channel must be created", channel)
        assertEquals(
            "channel must use IMPORTANCE_DEFAULT for status bar icon with muted sound",
            NotificationManager.IMPORTANCE_DEFAULT,
            channel.importance,
        )
        assertNull("channel must have no audio sound", channel.sound)
        assertFalse("channel must not vibrate", channel.shouldVibrate())
        assertFalse("channel must not show badge", channel.canShowBadge())
    }

    @Test
    fun `downloading state is ongoing, has progress, content intent, and small icon`() {
        val key = DemTileKey(50, 14)
        notifications.update(HorizonState.Downloading(key, 1, 3, 0.45f))

        val sbn = manager.activeNotifications.singleOrNull { it.id == TerrainNotifications.NOTIFICATION_ID }
        assertNotNull("notification must be posted", sbn)
        val notif = sbn!!.notification

        assertTrue("notification must be ongoing during download", (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertEquals("category must be progress", Notification.CATEGORY_PROGRESS, notif.category)
        assertNotNull("small icon must be present for system bar", notif.smallIcon)
        assertNotNull("content intent must be present for tap to open", notif.contentIntent)

        val extras = notif.extras
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        assertTrue("text must contain tile name", text.contains("N50E014"))
        assertTrue("text must contain percentage", text.contains("45 %"))
        assertEquals(45, extras.getInt(Notification.EXTRA_PROGRESS))
    }

    @Test
    fun `computing state is ongoing and updates title and progress immediately`() {
        val key = DemTileKey(50, 14)
        notifications.update(HorizonState.Downloading(key, 1, 1, 1.0f))

        // Phase change to Computing must not be suppressed by throttling
        notifications.update(HorizonState.Computing(0.60f))

        val sbn = manager.activeNotifications.singleOrNull { it.id == TerrainNotifications.NOTIFICATION_ID }
        assertNotNull(sbn)
        val notif = sbn!!.notification

        assertTrue("notification must be ongoing during skyline compute", (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        val extras = notif.extras
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        assertTrue("text must contain percentage", text.contains("60 %"))
        assertEquals(60, extras.getInt(Notification.EXTRA_PROGRESS))
    }

    @Test
    fun `ready state is not ongoing and has autoCancel enabled`() {
        val key = DemTileKey(50, 14)
        notifications.update(HorizonState.Downloading(key, 1, 1, 0.5f))

        val sampleElevations = FloatArray(HorizonProfile.SAMPLE_COUNT) { 5.0f }
        val profile = HorizonProfile(
            elevationsDegrees = sampleElevations,
            observer = GeoLocation(50.0, 14.0),
            observerGroundElevationMeters = 200.0,
            eyeHeightMeters = 1.7,
            maxRangeMeters = 50_000.0,
            generatedAtEpochMillis = System.currentTimeMillis(),
            coveredSamples = HorizonProfile.SAMPLE_COUNT,
        )

        notifications.update(HorizonState.Ready(profile))

        val sbn = manager.activeNotifications.singleOrNull { it.id == TerrainNotifications.NOTIFICATION_ID }
        assertNotNull(sbn)
        val notif = sbn!!.notification

        assertFalse("completed notification must not be ongoing", (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue("completed notification must auto cancel on tap", (notif.flags and Notification.FLAG_AUTO_CANCEL) != 0)
    }

    @Test
    fun `failed state is not ongoing and has autoCancel enabled`() {
        val key = DemTileKey(50, 14)
        notifications.update(HorizonState.Downloading(key, 1, 1, 0.5f))
        notifications.update(HorizonState.Failed(HorizonState.Failed.Reason.NETWORK))

        val sbn = manager.activeNotifications.singleOrNull { it.id == TerrainNotifications.NOTIFICATION_ID }
        assertNotNull(sbn)
        val notif = sbn!!.notification

        assertFalse("failed notification must not be ongoing", (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue("failed notification must auto cancel on tap", (notif.flags and Notification.FLAG_AUTO_CANCEL) != 0)
    }

    @Test
    fun `idle state cancels active notification`() {
        val key = DemTileKey(50, 14)
        notifications.update(HorizonState.Downloading(key, 1, 1, 0.5f))
        assertTrue(manager.activeNotifications.any { it.id == TerrainNotifications.NOTIFICATION_ID })

        notifications.update(HorizonState.Idle)
        assertFalse(manager.activeNotifications.any { it.id == TerrainNotifications.NOTIFICATION_ID })
    }

    @Test
    fun `cancel clears notification`() {
        val key = DemTileKey(50, 14)
        notifications.update(HorizonState.Downloading(key, 1, 1, 0.5f))
        assertTrue(manager.activeNotifications.any { it.id == TerrainNotifications.NOTIFICATION_ID })

        notifications.cancel()
        assertFalse(manager.activeNotifications.any { it.id == TerrainNotifications.NOTIFICATION_ID })
    }
}
