package com.dazix.caelis.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.text.format.Formatter
import androidx.test.core.app.ApplicationProvider
import com.dazix.caelis.R
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
    fun `channels are created with correct importance, silent and no vibration`() {
        val key = DemTileKey(50, 14)
        notifications.update(HorizonState.Downloading(key, 1, 3, 0.45f))

        val activeChannel = manager.getNotificationChannel(TerrainNotifications.CHANNEL_ID_ACTIVE)
        assertNotNull("active channel must be created", activeChannel)
        assertEquals(
            "active channel must use IMPORTANCE_DEFAULT for status bar icon with muted sound",
            NotificationManager.IMPORTANCE_DEFAULT,
            activeChannel.importance,
        )
        assertNull("active channel must have no audio sound", activeChannel.sound)
        assertFalse("active channel must not vibrate", activeChannel.shouldVibrate())
        assertFalse("active channel must not show badge", activeChannel.canShowBadge())

        val completeChannel = manager.getNotificationChannel(TerrainNotifications.CHANNEL_ID_COMPLETE)
        assertNotNull("complete channel must be created", completeChannel)
        assertEquals(
            "complete channel must use IMPORTANCE_LOW for silent section",
            NotificationManager.IMPORTANCE_LOW,
            completeChannel.importance,
        )
        assertNull("complete channel must have no audio sound", completeChannel.sound)
        assertFalse("complete channel must not vibrate", completeChannel.shouldVibrate())
        assertFalse("complete channel must not show badge", completeChannel.canShowBadge())
    }

    @Test
    fun `locating state is ongoing, indeterminate, on active channel`() {
        notifications.update(HorizonState.Locating())

        val sbn = manager.activeNotifications.singleOrNull { it.id == TerrainNotifications.NOTIFICATION_ID }
        assertNotNull("notification must be posted", sbn)
        val notif = sbn!!.notification

        assertEquals(TerrainNotifications.CHANNEL_ID_ACTIVE, notif.channelId)
        assertTrue("notification must be ongoing during locating", (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertEquals("category must be progress", Notification.CATEGORY_PROGRESS, notif.category)
        assertNotNull("small icon must be present for system bar", notif.smallIcon)
        assertNotNull("content intent must be present for tap to open", notif.contentIntent)

        val extras = notif.extras
        assertTrue("extras must indicate indeterminate progress", extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test
    fun `locating without satellites does not claim to wait for gps`() {
        notifications.update(HorizonState.Locating(satellites = false))

        val extras = manager.activeNotifications.single { it.id == TerrainNotifications.NOTIFICATION_ID }.notification.extras
        assertEquals(context.getString(R.string.horizon_notif_locating_network), extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(context.getString(R.string.horizon_status_locating_network), extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `downloading state is ongoing, has progress, content intent, and small icon on active channel`() {
        val key = DemTileKey(50, 14)
        notifications.update(HorizonState.Downloading(key, 1, 3, 0.45f))

        val sbn = manager.activeNotifications.singleOrNull { it.id == TerrainNotifications.NOTIFICATION_ID }
        assertNotNull("notification must be posted", sbn)
        val notif = sbn!!.notification

        assertEquals(TerrainNotifications.CHANNEL_ID_ACTIVE, notif.channelId)
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
    fun `downloading text says how much of the tile has arrived`() {
        val key = DemTileKey(50, 14)
        notifications.update(HorizonState.Downloading(key, 1, 3, 0.34f, receivedBytes = 4_100_000, totalBytes = 12_000_000))

        val text = manager.activeNotifications.single { it.id == TerrainNotifications.NOTIFICATION_ID }
            .notification.extras.getString(Notification.EXTRA_TEXT) ?: ""
        val expected = context.getString(
            R.string.horizon_download_size_of,
            Formatter.formatShortFileSize(context, 4_100_000),
            Formatter.formatShortFileSize(context, 12_000_000),
        )
        assertTrue("text must contain \"$expected\", was \"$text\"", text.contains(expected))
    }

    @Test
    fun `downloading text without a known length shows only what arrived`() {
        val key = DemTileKey(50, 14)
        notifications.update(HorizonState.Downloading(key, 1, 3, 0f, receivedBytes = 4_100_000))

        val text = manager.activeNotifications.single { it.id == TerrainNotifications.NOTIFICATION_ID }
            .notification.extras.getString(Notification.EXTRA_TEXT) ?: ""
        val received = Formatter.formatShortFileSize(context, 4_100_000)
        val sizeOf = context.getString(R.string.horizon_download_size_of, received, "")
        assertTrue("text must contain \"$received\", was \"$text\"", text.contains(received))
        assertFalse("no total may be invented, was \"$text\"", text.contains(sizeOf))
    }

    @Test
    fun `computing state is ongoing and updates title and progress immediately on active channel`() {
        val key = DemTileKey(50, 14)
        notifications.update(HorizonState.Downloading(key, 1, 1, 1.0f))

        // Phase change to Computing must not be suppressed by throttling
        notifications.update(HorizonState.Computing(0.60f))

        val sbn = manager.activeNotifications.singleOrNull { it.id == TerrainNotifications.NOTIFICATION_ID }
        assertNotNull(sbn)
        val notif = sbn!!.notification

        assertEquals(TerrainNotifications.CHANNEL_ID_ACTIVE, notif.channelId)
        assertTrue("notification must be ongoing during skyline compute", (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        val extras = notif.extras
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        assertTrue("text must contain percentage", text.contains("60 %"))
        assertEquals(60, extras.getInt(Notification.EXTRA_PROGRESS))
    }

    @Test
    fun `ready state is on complete channel, not ongoing, and has autoCancel enabled`() {
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

        assertEquals(TerrainNotifications.CHANNEL_ID_COMPLETE, notif.channelId)
        assertFalse("completed notification must not be ongoing", (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue("completed notification must auto cancel on tap", (notif.flags and Notification.FLAG_AUTO_CANCEL) != 0)
    }

    @Test
    fun `failed state is on complete channel, not ongoing, and has autoCancel enabled`() {
        val key = DemTileKey(50, 14)
        notifications.update(HorizonState.Downloading(key, 1, 1, 0.5f))
        notifications.update(HorizonState.Failed(HorizonState.Failed.Reason.NETWORK))

        val sbn = manager.activeNotifications.singleOrNull { it.id == TerrainNotifications.NOTIFICATION_ID }
        assertNotNull(sbn)
        val notif = sbn!!.notification

        assertEquals(TerrainNotifications.CHANNEL_ID_COMPLETE, notif.channelId)
        assertFalse("failed notification must not be ongoing", (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue("failed notification must auto cancel on tap", (notif.flags and Notification.FLAG_AUTO_CANCEL) != 0)
    }

    @Test
    fun `failed state due to location timeout is on complete channel`() {
        notifications.update(HorizonState.Locating())
        notifications.update(HorizonState.Failed(HorizonState.Failed.Reason.LOCATION))

        val sbn = manager.activeNotifications.singleOrNull { it.id == TerrainNotifications.NOTIFICATION_ID }
        assertNotNull(sbn)
        val notif = sbn!!.notification

        assertEquals(TerrainNotifications.CHANNEL_ID_COMPLETE, notif.channelId)
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
