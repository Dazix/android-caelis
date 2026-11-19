package com.dazix.caelis.ui

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.dazix.caelis.verify.CaptureVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `minSdk` is 30, and three of the permissions the app reads changed shape after Android 11: the photos
 * permission (none at all from 13, where capture verification watches insert notifications), the
 * notification dialog (POST_NOTIFICATIONS from 13) and the exact-alarm grant (from 12). Checking a
 * newer name on an older platform compiles, runs and answers "denied" forever, so each one is pinned
 * here on both sides of the level it changed at.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionsPlatformLevelTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    @Config(sdk = [30])
    fun `android 11 reads photos through READ_EXTERNAL_STORAGE`() {
        assertEquals(Manifest.permission.READ_EXTERNAL_STORAGE, Permissions.photos())
        assertFalse(Permissions.read(context).captureVerificationReady)
        assertFalse(CaptureVerifier(context).available())

        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)

        assertTrue(Permissions.photosNeedRuntimeGrant())

        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)

        assertTrue(Permissions.read(context).captureVerificationReady)
        assertTrue(CaptureVerifier(context).available())
    }

    /**
     * Google Play refused READ_MEDIA_IMAGES for capture verification, so from Android 13 it must work
     * with no photos permission granted at all, from MediaStore's insert notifications.
     */
    @Test
    @Config(sdk = [33])
    fun `android 13 verifies captures without any photos permission`() {
        assertFalse(Permissions.photosNeedRuntimeGrant())
        assertTrue(Permissions.read(context).captureVerificationReady)

        val verifier = CaptureVerifier(context)
        assertTrue(verifier.available())
        val baseline = verifier.newestToken()
        assertTrue("the insert observer could not be registered", baseline != null)
        assertFalse(verifier.hasNewImageSince(baseline))

        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val row = ContentUris.withAppendedId(images, 42L)
        notify(images, row, ContentResolver.NOTIFY_UPDATE)
        assertFalse("an UPDATE is not a new photo", verifier.hasNewImageSince(baseline))

        notify(images, row, ContentResolver.NOTIFY_INSERT)
        assertTrue(verifier.hasNewImageSince(baseline))
        assertFalse(
            "the next shot's baseline must not see the previous shot's photo",
            verifier.hasNewImageSince(verifier.newestToken()),
        )

        verifier.close()
        val afterClose = verifier.newestToken()
        notify(images, row, ContentResolver.NOTIFY_INSERT)
        assertTrue("a new session must watch again", verifier.hasNewImageSince(afterClose))
        verifier.close()
        assertTrue(
            "close() must unregister the observer",
            shadowOf(context.contentResolver).getContentObservers(images).isEmpty(),
        )
    }

    /**
     * Delivers a change the way MediaStore does, with its flags: Robolectric's notifyChange calls the
     * flag-less onChange, which would never reach the verifier's observer.
     */
    private fun notify(collection: Uri, row: Uri, flags: Int) {
        val observers = shadowOf(context.contentResolver).getContentObservers(collection)
        assertEquals("exactly one observer on the images collection", 1, observers.size)
        observers.forEach { it.onChange(false, listOf(row), flags) }
    }

    @Test
    @Config(sdk = [30])
    fun `android 11 notifications follow the app switch, not POST_NOTIFICATIONS`() {
        assertFalse(Permissions.notificationsNeedRuntimeGrant())
        assertTrue(
            "notifications are on by default below Android 13",
            Permissions.read(context).notificationsGranted,
        )

        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationsEnabled(false)
        assertFalse(Permissions.read(context).notificationsGranted)

        val intent = Permissions.notificationSettings(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    @Config(sdk = [33])
    fun `android 13 notifications need POST_NOTIFICATIONS`() {
        assertTrue(Permissions.notificationsNeedRuntimeGrant())
        assertFalse(Permissions.read(context).notificationsGranted)

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(Permissions.read(context).notificationsGranted)
    }

    @Test
    @Config(sdk = [30])
    fun `android 11 allows exact alarms without a grant and offers no alarm screen`() {
        assertFalse(Permissions.exactAlarmsNeedGrant())
        assertTrue(Permissions.read(context).exactAlarmsAllowed)
        assertEquals(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Permissions.exactAlarmSettings(context).action,
        )
    }

    @Test
    @Config(sdk = [31])
    fun `android 12 sends the user to the Alarms and reminders switch`() {
        assertTrue(Permissions.exactAlarmsNeedGrant())
        assertEquals(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Permissions.exactAlarmSettings(context).action,
        )
    }
}
