package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseTimestampPolicyTest {

    private val ms = 1_000_000L

    @Test
    fun frameInstantIsTheMiddleOfExposureAndReadout() {
        // 10 ms exposure, 20 ms rolling shutter: the frame's centre row is lit 15 ms after its start.
        assertEquals(1_000 * ms + 15 * ms, PoseTimestampPolicy.frameInstant(1_000 * ms, 10 * ms, 20 * ms))
    }

    @Test
    fun missingExposureAndSkewCountAsZero() {
        assertEquals(1_000 * ms, PoseTimestampPolicy.frameInstant(1_000 * ms, -1L, -1L))
    }

    @Test
    fun noCameraTimestampMeansNoInstant() {
        assertEquals(0L, PoseTimestampPolicy.frameInstant(0L, 10 * ms, 10 * ms))
    }

    @Test
    fun aFreshFrameIsDrawnAtItsOwnInstant() {
        val frame = 5_000 * ms
        assertEquals(frame, PoseTimestampPolicy.renderTimestamp(frame, frame + 70 * ms, displayLagNs = 0L))
    }

    @Test
    fun displayLagShiftsTheInstantLater() {
        val frame = 5_000 * ms
        assertEquals(frame + 16 * ms, PoseTimestampPolicy.renderTimestamp(frame, frame + 70 * ms, 16 * ms))
    }

    @Test
    fun displayLagNeverReachesPastNow() {
        val frame = 5_000 * ms
        assertEquals(frame + 10 * ms, PoseTimestampPolicy.renderTimestamp(frame, frame + 10 * ms, 40 * ms))
    }

    @Test
    fun noFrameMeansLive() {
        assertEquals(0L, PoseTimestampPolicy.renderTimestamp(0L, 5_000 * ms, 0L))
    }

    @Test
    fun aStalledCameraMeansLive() {
        val frame = 5_000 * ms
        val now = frame + PoseTimestampPolicy.MAX_FRAME_AGE_NS + 1
        assertEquals(0L, PoseTimestampPolicy.renderTimestamp(frame, now, 0L))
    }

    @Test
    fun aFrameFromTheFutureIsAClockMismatchAndMeansLive() {
        // A camera on a different time base reports instants unrelated to the sensors' clock.
        assertEquals(0L, PoseTimestampPolicy.renderTimestamp(6_000 * ms, 5_000 * ms, 0L))
    }
}
