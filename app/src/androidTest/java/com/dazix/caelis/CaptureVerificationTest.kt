package com.dazix.caelis

import android.content.ContentValues
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.caelis.data.SessionRepository
import com.dazix.caelis.domain.SessionConfig
import com.dazix.caelis.domain.StopConditions
import com.dazix.caelis.domain.StopReason
import com.dazix.caelis.domain.TriggerMode
import com.dazix.caelis.verify.CaptureVerifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `verifyCaptures` measured against a MediaStore that behaves like a real camera app's: the row for a
 * frame appears well after the camera has stopped being busy.
 *
 * This is the case the unit tests cannot reach — it needs a live engine, a real MediaStore and real
 * time — and the one that regressed unnoticed: verification used to look exactly once, at
 * `CameraProfile.captureLatencyMs` after the tap, so every good capture on a Pixel counted as missing
 * and the engine re-tapped every single shot (two frames per requested shot).
 *
 * [rowThatAppearsLateStillCountsAsOneShot] is the regression: rows are published deliberately later
 * than the profile's capture latency and there must still be exactly one tap per shot.
 * [captureThatNeverAppearsIsRetriedExactlyOnce] holds the feature down from the other side, so
 * "no more re-taps" can never be satisfied by quietly dropping the retry.
 */
@RunWith(AndroidJUnit4::class)
class CaptureVerificationTest {

    private val inserted = ArrayList<Uri>()

    @Volatile
    private var publishing = false

    @Before
    fun setUp() {
        SessionHarness.requireServiceBound()
        SessionHarness.reset()
        // Granted by scripts/a11y_setup.sh and restored by a11y_teardown.sh. Without it the engine
        // skips verification altogether and both tests below would pass without testing anything.
        assertTrue(
            "READ_MEDIA_IMAGES is not granted, so nothing here would exercise verifyCaptures; " +
                "run this through scripts/run_instrumented.sh",
            CaptureVerifier(SessionHarness.context).available(),
        )
        deleteOwnRows()
    }

    @After
    fun tearDown() {
        publishing = false
        SessionHarness.stopSessionQuietly()
        SessionHarness.closeFakeCamera()
        deleteOwnRows()
    }

    /**
     * The camera "publishes" each frame [PUBLISH_DELAY_MS] after the tap — later than the generic
     * profile's 900 ms capture latency, which is what a Pixel does with HDR+ — and every shot must
     * still cost exactly one shutter press.
     */
    @Test
    fun rowThatAppearsLateStillCountsAsOneShot() {
        val config = SessionConfig(
            mode = TriggerMode.Timelapse(SessionHarness.appPackage),
            intervalMs = INTERVAL_MS,
            startDelayMs = 0L,
            stop = StopConditions(maxShots = SHOTS),
            verifyCaptures = true,
        )

        SessionHarness.launchFakeCamera()
        startPublisher()
        SessionHarness.startSession(config)

        val stopped = SessionHarness.awaitStopped(INTERVAL_MS * SHOTS + VERIFIED_SLACK_MS)
        assertNotNull(
            "session never stopped; snapshot was ${SessionRepository.snapshot.value}",
            stopped,
        )
        assertEquals(StopReason.COMPLETED, stopped?.reason)

        // After the stop, so a re-tap that arrived late still shows up in the count.
        SessionHarness.idle(SETTLE_MS)
        val taps = SessionHarness.readTaps()
        assertEquals(
            "each verified shot must cost exactly one shutter press, log was $taps " +
                "(${inserted.size} row(s) published)",
            SHOTS,
            taps.size,
        )
        assertEquals(SHOTS, SessionRepository.snapshot.value.shotsTaken)
        assertTrue(
            "a capture the verifier could see must not be reported as unverified",
            SessionRepository.snapshot.value.warning !=
                SessionHarness.context.getString(R.string.session_warning_capture_unverified),
        )
    }

    /**
     * Nothing ever lands in MediaStore — the fake camera writes no files — so the window has to
     * expire, the engine has to press the shutter once more, and that second press must not be
     * counted as a second shot.
     */
    @Test
    fun captureThatNeverAppearsIsRetriedExactlyOnce() {
        val config = SessionConfig(
            mode = TriggerMode.Timelapse(SessionHarness.appPackage),
            intervalMs = INTERVAL_MS,
            startDelayMs = 0L,
            stop = StopConditions(maxShots = 1),
            verifyCaptures = true,
        )

        SessionHarness.launchFakeCamera()
        SessionHarness.startSession(config)

        val taps = SessionHarness.awaitTaps(2, UNVERIFIED_TIMEOUT_MS)
        assertEquals("expected the shot and exactly one retry, log was $taps", 2, taps.size)
        assertTrue(
            "the retry must wait out the whole verification window; gap was " +
                "${taps[1].elapsedRealtimeMs - taps[0].elapsedRealtimeMs} ms",
            taps[1].elapsedRealtimeMs - taps[0].elapsedRealtimeMs >= MIN_RETRY_GAP_MS,
        )

        val stopped = SessionHarness.awaitStopped(UNVERIFIED_TIMEOUT_MS)
        assertNotNull(
            "session never stopped; snapshot was ${SessionRepository.snapshot.value}",
            stopped,
        )
        assertEquals(StopReason.COMPLETED, stopped?.reason)
        assertEquals(
            "the retry re-tapped the same frame and must not count as a second shot",
            1,
            SessionRepository.snapshot.value.shotsTaken,
        )
        assertEquals(
            SessionHarness.context.getString(R.string.session_warning_capture_unverified),
            SessionRepository.snapshot.value.warning,
        )
        SessionHarness.idle(SETTLE_MS)
        assertEquals(
            "one shot may be retried once and no more, log was ${SessionHarness.readTaps()}",
            2,
            SessionHarness.readTaps().size,
        )
    }

    // ---- the stand-in camera writer ------------------------------------------------------------

    /**
     * Watches the tap log and inserts one image row [PUBLISH_DELAY_MS] after each tap, which is the
     * only part of a real camera app the verifier can see. A daemon thread rather than a handler: it
     * must not share the main looper the engine schedules its own work on.
     */
    private fun startPublisher() {
        publishing = true
        val thread = Thread {
            var published = 0
            val deadline = SystemClock.elapsedRealtime() + PUBLISHER_LIFETIME_MS
            while (publishing && SystemClock.elapsedRealtime() < deadline) {
                val taps = SessionHarness.readTaps()
                if (taps.size <= published) {
                    SystemClock.sleep(PUBLISH_POLL_MS)
                    continue
                }
                val dueAt = taps[published].elapsedRealtimeMs + PUBLISH_DELAY_MS
                val wait = dueAt - SystemClock.elapsedRealtime()
                if (wait > 0L) SystemClock.sleep(wait)
                insertRow()
                published++
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    private fun insertRow() {
        val resolver = SessionHarness.context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$NAME_PREFIX${System.nanoTime()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
        }
        val uri = runCatching {
            resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            )
        }.getOrNull() ?: return
        // Bytes, so the row is a file the media scanner will not clean up under us mid-test.
        runCatching { resolver.openOutputStream(uri)?.use { it.write(JPEG_HEADER) } }
        synchronized(inserted) { inserted += uri }
    }

    /**
     * Removes every row this test class owns — the ones it recorded and, by display name, any left
     * behind by a run that died before its teardown. Rows the app did not create are never touched.
     */
    private fun deleteOwnRows() {
        val resolver = SessionHarness.context.contentResolver
        synchronized(inserted) {
            inserted.forEach { runCatching { resolver.delete(it, null, null) } }
            inserted.clear()
        }
        runCatching {
            resolver.delete(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("$NAME_PREFIX%"),
            )
        }
    }

    private companion object {
        const val SHOTS = 3
        const val INTERVAL_MS = 4_000L

        /**
         * Later than the generic profile's `captureLatencyMs` (900 ms) and earlier than
         * [INTERVAL_MS], so the row is genuinely late but the cadence is still achievable.
         */
        const val PUBLISH_DELAY_MS = 2_500L
        const val PUBLISH_POLL_MS = 100L
        const val PUBLISHER_LIFETIME_MS = 90_000L

        /** `SessionEngine.VERIFY_GRACE_MS` (10 s), which the whole window has to be at least. */
        const val MIN_RETRY_GAP_MS = 10_000L

        const val VERIFIED_SLACK_MS = 20_000L
        const val UNVERIFIED_TIMEOUT_MS = 60_000L
        const val SETTLE_MS = 3_000L

        const val NAME_PREFIX = "caelis-verify-"

        /**
         * `DCIM/Camera`, not `Pictures/…`: the verifier now only accepts a row a camera could have
         * written, judged by owner *and* by location. A row under `Pictures/` would be accepted here
         * solely through the "owned by this app" escape hatch that exists for this very suite, so the
         * test would pass without exercising the location half of the rule at all — and would keep
         * passing if that half broke. Rows are removed again by [deleteOwnRows], which matches on
         * [NAME_PREFIX] and never touches a photo this suite did not create.
         */
        const val RELATIVE_PATH = "DCIM/Camera"

        /** Enough of a JPEG for a MediaStore row to be backed by a real, non-empty file. */
        val JPEG_HEADER = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xD9.toByte(),
        )
    }
}
