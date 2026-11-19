package com.dazix.caelis.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether the app tells its user a photo was taken.
 *
 * Before this rule existed, verification accepted the newest image row from any source, so a
 * screenshot taken during a session — or a picture arriving in a chat — counted as proof that the
 * camera had saved a frame. Every case below is a way that lie could be told, or a real capture the
 * rule must not reject (a rejection costs an unwanted second photo and a warning).
 */
class CaptureMatchingTest {

    // ---- what the camera saved -------------------------------------------------------------------

    @Test
    fun `a photo in the camera folder counts`() {
        assertTrue(CameraImageRule.isPlausibleCameraRow(cameraRow(), CAMERA))
    }

    @Test
    fun `a row the camera app owns counts wherever it saved it`() {
        val elsewhere = row(relativePath = "Pictures/GoogleCamera/", bucket = "GoogleCamera", owner = CAMERA)
        assertTrue(CameraImageRule.isPlausibleCameraRow(elsewhere, CAMERA))
    }

    /** Most rows carry no owner at all; the folder has to be enough on its own. */
    @Test
    fun `an unowned row in the camera folder counts even with no expected package`() {
        assertTrue(CameraImageRule.isPlausibleCameraRow(cameraRow(owner = null), null))
    }

    @Test
    fun `a row indexed by the media scanner is judged by its folder, not its owner`() {
        val scanned = cameraRow(owner = "com.android.providers.media.module")
        assertTrue(CameraImageRule.isPlausibleCameraRow(scanned, CAMERA))
        assertFalse(
            CameraImageRule.isPlausibleCameraRow(
                row(
                    relativePath = "Pictures/Screenshots/",
                    bucket = "Screenshots",
                    owner = "com.android.providers.media.module",
                ),
                CAMERA,
            ),
        )
    }

    /** A legacy row can arrive with only a bucket name. `DCIM/100ANDRO` is still a camera. */
    @Test
    fun `a row with no relative path falls back to known camera buckets`() {
        assertTrue(
            CameraImageRule.isPlausibleCameraRow(
                row(relativePath = null, bucket = "100ANDRO", owner = null),
                CAMERA,
            ),
        )
        assertFalse(
            CameraImageRule.isPlausibleCameraRow(
                row(relativePath = null, bucket = "WhatsApp Images", owner = null),
                CAMERA,
            ),
        )
    }

    // ---- what must never count -------------------------------------------------------------------

    @Test
    fun `a screenshot is not a capture`() {
        val pixelStyle = row(relativePath = "Pictures/Screenshots/", bucket = "Screenshots", owner = null)
        val samsungStyle = row(relativePath = "DCIM/Screenshots/", bucket = "Screenshots", owner = null)
        val systemUi = row(relativePath = "DCIM/Screenshots/", bucket = "Screenshots", owner = "com.android.systemui")

        assertFalse("Pictures/Screenshots", CameraImageRule.isPlausibleCameraRow(pixelStyle, CAMERA))
        assertFalse("DCIM/Screenshots", CameraImageRule.isPlausibleCameraRow(samsungStyle, CAMERA))
        assertFalse("owned by SystemUI", CameraImageRule.isPlausibleCameraRow(systemUi, CAMERA))
    }

    @Test
    fun `an image arriving in a chat is not a capture`() {
        val chat = row(
            relativePath = "Pictures/WhatsApp/Media/WhatsApp Images/",
            bucket = "WhatsApp Images",
            owner = "com.whatsapp",
        )
        val download = row(relativePath = "Download/", bucket = "Download", owner = "com.android.chrome")

        assertFalse(CameraImageRule.isPlausibleCameraRow(chat, CAMERA))
        assertFalse(CameraImageRule.isPlausibleCameraRow(download, CAMERA))
        assertFalse("still not a capture when no camera app is named", CameraImageRule.isPlausibleCameraRow(chat, null))
    }

    /**
     * The nastiest case: another app saving into `DCIM/Camera` while a session runs. The folder alone
     * would accept it; the owner is what gives it away.
     */
    @Test
    fun `another app writing into the camera folder is rejected when the owner gives it away`() {
        val impostor = cameraRow(owner = "com.example.gallery")
        assertFalse(CameraImageRule.isPlausibleCameraRow(impostor, CAMERA))
    }

    /**
     * The instrumented suite's stand-in camera is this app itself, and no production path ever creates
     * an image row under its name.
     */
    @Test
    fun `a row this app created counts, so the on-device harness still exercises the real path`() {
        val fromHarness = row(
            relativePath = "Pictures/CaelisTest/",
            bucket = "CaelisTest",
            owner = SELF,
        )
        assertTrue(CameraImageRule.isPlausibleCameraRow(fromHarness, CAMERA, SELF))
        assertFalse("without the self package it is just another app's row", CameraImageRule.isPlausibleCameraRow(fromHarness, CAMERA))
    }

    // ---- the shot window -------------------------------------------------------------------------

    @Test
    fun `only rows added after the shutter was pressed count`() {
        assertTrue("inside", CameraImageRule.isInWindow(WINDOW_START + 4L, WINDOW_START, NOW))
        assertTrue("at the moment the window opened", CameraImageRule.isInWindow(WINDOW_START, WINDOW_START, NOW))
        assertFalse(
            "a photo from before the shot",
            CameraImageRule.isInWindow(WINDOW_START - 60L, WINDOW_START, NOW),
        )
    }

    /**
     * `DATE_ADDED` is whole seconds written by the provider's own clock, so a frame saved in the same
     * second as the baseline can read fractionally early. The tolerance is small enough that an older
     * photo cannot slip in.
     */
    @Test
    fun `a second of provider clock skew is tolerated on both edges`() {
        assertTrue(
            CameraImageRule.isInWindow(WINDOW_START - CameraImageRule.CLOCK_SKEW_SEC, WINDOW_START, NOW),
        )
        assertFalse(
            CameraImageRule.isInWindow(WINDOW_START - CameraImageRule.CLOCK_SKEW_SEC - 1L, WINDOW_START, NOW),
        )
        assertTrue(CameraImageRule.isInWindow(NOW + CameraImageRule.CLOCK_SKEW_SEC, WINDOW_START, NOW))
    }

    /** A row dated in the future would otherwise satisfy every window for the rest of the session. */
    @Test
    fun `a row dated in the future is not proof of anything`() {
        assertFalse(CameraImageRule.isInWindow(NOW + 86_400L, WINDOW_START, NOW))
    }

    @Test
    fun `a row with no date is never accepted`() {
        assertFalse(CameraImageRule.isInWindow(0L, WINDOW_START, NOW))
        assertFalse(CameraImageRule.accepts(cameraRow(dateAddedSec = 0L), CAMERA, WINDOW_START, NOW))
    }

    @Test
    fun `accepts needs the folder and the window to agree`() {
        assertTrue(CameraImageRule.accepts(cameraRow(), CAMERA, WINDOW_START, NOW))
        assertFalse(
            "right folder, wrong time",
            CameraImageRule.accepts(cameraRow(dateAddedSec = WINDOW_START - 30L), CAMERA, WINDOW_START, NOW),
        )
        assertFalse(
            "right time, wrong folder",
            CameraImageRule.accepts(
                row(relativePath = "Pictures/Screenshots/", bucket = "Screenshots", owner = null),
                CAMERA,
                WINDOW_START,
                NOW,
            ),
        )
    }

    // ---- the baseline token ----------------------------------------------------------------------

    @Test
    fun `the token carries the window start and the row that was newest`() {
        val token = CaptureToken.encode(WINDOW_START, id = 4_211L, dateAddedSec = WINDOW_START - 90L)

        assertEquals(WINDOW_START, CaptureToken.windowStartOf(token))
        assertEquals("4211/${WINDOW_START - 90L}", CaptureToken.identityOf(token))
    }

    /** An empty camera folder is an ordinary state: there is a window, there is just no earlier row. */
    @Test
    fun `a token with no baseline row still carries a usable window`() {
        val token = CaptureToken.encode(WINDOW_START, id = null, dateAddedSec = null)

        assertEquals(WINDOW_START, CaptureToken.windowStartOf(token))
        assertNull(CaptureToken.identityOf(token))
    }

    /**
     * No window means no way to tell this shot's frame from last week's. The verifier turns that into
     * "cannot confirm" — a warning — and never into a capture.
     */
    @Test
    fun `a missing or unrecognised token yields no window at all`() {
        assertNull(CaptureToken.windowStartOf(null))
        assertNull(CaptureToken.windowStartOf(""))
        assertNull("the pre-window format must not be mistaken for a window", CaptureToken.windowStartOf("41/1760000000"))
        assertNull(CaptureToken.windowStartOf("v9|1|2|3"))
        assertNull(CaptureToken.windowStartOf("v2|not-a-number|2|3"))
    }

    // ---- Android 13+: insert notifications -------------------------------------------------------

    /**
     * Without READ_MEDIA_IMAGES the row behind a notification is unreadable, so the count of INSERTs
     * is all the proof there is. The camera's own follow-up UPDATEs (the pending row finished) and a
     * delete must not count, or one frame reads as several and a deleted photo as a new one.
     */
    @Test
    fun `only an insert after the baseline counts`() {
        val log = ImageInsertLog()
        log.record(android.content.ContentResolver.NOTIFY_INSERT)
        val baseline = log.mark()
        assertFalse("an insert before the tap is not this shot's frame", log.hasInsertAfter(baseline))

        log.record(android.content.ContentResolver.NOTIFY_UPDATE)
        log.record(android.content.ContentResolver.NOTIFY_DELETE)
        log.record(0)
        assertFalse(log.hasInsertAfter(baseline))

        log.record(
            android.content.ContentResolver.NOTIFY_INSERT or
                android.content.ContentResolver.NOTIFY_SYNC_TO_NETWORK,
        )
        assertTrue(log.hasInsertAfter(baseline))
    }

    @Test
    fun `the insert token round-trips and is never read as the other format`() {
        assertEquals(7L, CaptureToken.insertMarkOf(CaptureToken.encodeInserts(7L)))
        assertNull(CaptureToken.insertMarkOf(null))
        assertNull(CaptureToken.insertMarkOf("i1|x"))
        assertNull(
            "a row token must not pass as an insert mark",
            CaptureToken.insertMarkOf(CaptureToken.encode(WINDOW_START, 1L, 2L)),
        )
        assertNull(
            "an insert mark must not pass as a window",
            CaptureToken.windowStartOf(CaptureToken.encodeInserts(7L)),
        )
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private fun cameraRow(
        owner: String? = CAMERA,
        dateAddedSec: Long = WINDOW_START + 6L,
    ) = row(relativePath = "DCIM/Camera/", bucket = "Camera", owner = owner, dateAddedSec = dateAddedSec)

    private fun row(
        relativePath: String?,
        bucket: String?,
        owner: String?,
        dateAddedSec: Long = WINDOW_START + 6L,
        id: Long = 9_001L,
    ) = ImageRow(
        id = id,
        dateAddedSec = dateAddedSec,
        relativePath = relativePath,
        bucket = bucket,
        ownerPackage = owner,
    )

    private companion object {
        const val CAMERA = "com.google.android.GoogleCamera"
        const val SELF = "com.dazix.caelis"

        /** Wall-clock seconds; the window opens at the tap and is checked a few seconds later. */
        const val WINDOW_START = 1_760_000_000L
        const val NOW = WINDOW_START + 8L
    }
}
