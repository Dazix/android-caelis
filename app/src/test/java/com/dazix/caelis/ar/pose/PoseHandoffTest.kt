package com.dazix.caelis.ar.pose

import com.dazix.caelis.ar.OrientationTracker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-off between the sensors' pose and ARCore's, with the sensors faked as an ideal compass
 * and ARCore as a script. Every expected matrix is derived from rotation formulas in
 * [PoseWorld], not by calling the code under test.
 */
class PoseHandoffTest {

    private val ms = 1_000_000L
    private val frame = 33 * ms
    private var worldYaw = 123.0

    private val sensor = FakeSensorPose(worldYawDegrees = { worldYaw }, deviceAt = { PoseWorld.device((it / frame).toInt()) })
    private val handoff = PoseHandoff(sensor)
    private val out = FloatArray(16)

    private fun feed(i: Int, tracking: ArTrackingState = ArTrackingState.TRACKING, consented: Boolean = true, zoom: Float = 1f) {
        val s = arSample(i * frame, tracking, deviceIndex = i)
        handoff.onSample(s, nowNs = i * frame, consented = consented, zoomRatio = zoom)
    }

    /** Runs the stable-tracking part of a session until ARCore draws: ~1 s to converge + 0.5 s hysteresis. */
    private fun runUntilArCore(from: Int = 0, to: Int = 90): Int {
        for (i in from until to) {
            feed(i)
            if (handoff.kind == PoseKind.ARCORE) return i
        }
        throw AssertionError("never handed to ARCore")
    }

    private fun expectedMatrix(i: Int, calAz: Float = 0f, calPitch: Float = 0f): FloatArray {
        val enu = PoseWorld.as4x4(PoseWorld.truth(worldYaw, PoseWorld.device(i)))
        val result = FloatArray(16)
        OrientationTracker.applyCalibration(enu, 0f, calAz, calPitch, FloatArray(16), result)
        return result
    }

    private fun assertMatrix(expected: FloatArray, actual: FloatArray, tolerance: Float = 2e-3f) {
        for (i in 0 until 16) assertEquals("element $i", expected[i], actual[i], tolerance)
    }

    @Test
    fun `starts on the sensors and stays there until the heading has converged`() {
        assertEquals(PoseKind.SENSOR, handoff.kind)
        // Twenty frames of perfect tracking is not enough samples for the fusion to call it stable.
        for (i in 0 until 20) feed(i)
        assertEquals(PoseKind.SENSOR, handoff.kind)
        assertFalse(handoff.renderMatrix(0, out))
    }

    @Test
    fun `hands to ARCore once tracking and heading are both stable, and its pose is the sensors' north`() {
        val i = runUntilArCore()
        assertTrue("needs ~30 samples and the enter delay", i >= 30)
        assertTrue(handoff.renderMatrix(i * frame, out))
        assertMatrix(expectedMatrix(i), out)
    }

    @Test
    fun `the standing calibration is applied exactly as the sensor path applies it, and a nudge needs no new frame`() {
        val i = runUntilArCore()
        sensor.calibrationAzimuthDegrees = 5f
        sensor.calibrationPitchDegrees = -2f
        assertTrue(handoff.renderMatrix(i * frame, out))
        assertMatrix(expectedMatrix(i, 5f, -2f), out)

        // Dragged further, between two camera frames: the overlay follows on the very next draw.
        sensor.calibrationAzimuthDegrees = 6.5f
        assertTrue(handoff.renderMatrix(i * frame, out))
        assertMatrix(expectedMatrix(i, 6.5f, -2f), out)
    }

    @Test
    fun `the calibration is not in the north reference, so it is not counted twice`() {
        // The reference the fake hands over is the uncalibrated truth; a calibration set before ARCore
        // ever starts must not leak into the yaw.
        sensor.calibrationAzimuthDegrees = 12f
        val i = runUntilArCore()
        assertTrue(handoff.renderMatrix(i * frame, out))
        assertMatrix(expectedMatrix(i, 12f, 0f), out)
    }

    @Test
    fun `a paused session hands back to the sensors on that very frame`() {
        val i = runUntilArCore()
        feed(i + 1, ArTrackingState.PAUSED)
        assertEquals(PoseKind.SENSOR, handoff.kind)
        assertFalse(handoff.renderMatrix((i + 1) * frame, out))
    }

    @Test
    fun `a stopped session does too`() {
        val i = runUntilArCore()
        feed(i + 1, ArTrackingState.STOPPED)
        assertEquals(PoseKind.SENSOR, handoff.kind)
    }

    @Test
    fun `a session that stops reporting is treated as lost`() {
        val i = runUntilArCore()
        handoff.tick(i * frame + PoseHandoff.STALE_NANOS, consented = true, zoomRatio = 1f)
        assertEquals(PoseKind.ARCORE, handoff.kind)
        handoff.tick(i * frame + PoseHandoff.STALE_NANOS + 1, consented = true, zoomRatio = 1f)
        assertEquals(PoseKind.SENSOR, handoff.kind)
    }

    @Test
    fun `tracking regained after a loss starts the alignment over and goes back to ARCore only when it is stable again`() {
        val i = runUntilArCore()
        feed(i + 1, ArTrackingState.PAUSED)
        // Relocalisation left ARCore's world turned by another 50 degrees. The old yaw is wrong now.
        worldYaw += 50.0
        var back = -1
        for (k in i + 2 until i + 120) {
            feed(k)
            if (back < 0 && handoff.kind == PoseKind.ARCORE) back = k
            if (k < i + 30) assertEquals("must not go back before the estimate converged (frame $k)", PoseKind.SENSOR, handoff.kind)
        }
        assertTrue("went back to ARCore", back > 0)
        assertTrue(handoff.renderMatrix((i + 119) * frame, out))
        assertMatrix(expectedMatrix(i + 119), out)
    }

    @Test
    fun `a new session is a new world even if tracking never dropped`() {
        val i = runUntilArCore()
        worldYaw -= 70.0
        // The epoch changes (resume): the first frame of the new session must not be drawn with the old yaw.
        val s = arSample((i + 1) * frame, ArTrackingState.TRACKING, deviceIndex = i + 1)
        s.sessionEpoch = 1
        handoff.onSample(s, (i + 1) * frame, consented = true, zoomRatio = 1f)
        assertEquals(PoseKind.SENSOR, handoff.kind)
    }

    @Test
    fun `withdrawn consent and any zoom other than 1x are the sensors immediately`() {
        var i = runUntilArCore()
        feed(++i, consented = false)
        assertEquals(PoseKind.SENSOR, handoff.kind)
        i = runUntilArCore(i + 1, i + 90)
        feed(++i, zoom = 2f)
        assertEquals(PoseKind.SENSOR, handoff.kind)
    }

    @Test
    fun `an unreliable compass keeps the pose on the sensors however steady ARCore is`() {
        sensor.headingAccuracy = 1
        for (i in 0 until 200) feed(i)
        assertEquals(PoseKind.SENSOR, handoff.kind)
    }

    @Test
    fun `a heading the user has locked counts as measured`() {
        // OrientationTracker reports high accuracy once the gyro holds the heading the user aligned.
        sensor.headingAccuracy = 3
        val i = runUntilArCore()
        assertTrue(i < 90)
    }

    @Test
    fun `without a sensor pose from the frame's own instant there is no yaw sample`() {
        sensor.hasHistory = false
        for (i in 0 until 200) feed(i)
        assertEquals(PoseKind.SENSOR, handoff.kind)
    }

    @Test
    fun `ending the session forgets the world`() {
        val i = runUntilArCore()
        handoff.onSessionEnded((i + 1) * frame)
        assertEquals(PoseKind.SENSOR, handoff.kind)
        assertFalse(handoff.arCore.headingConverged)
        assertFalse(handoff.renderMatrix(0, out))
    }

    @Test
    fun `Geospatial's heading wins over a compass that is off`() {
        // The compass tracker is 15 degrees off the real north; Geospatial knows better.
        val truth = 123.0
        val compassWorld = truth + 15.0
        val geoSensor = FakeSensorPose(worldYawDegrees = { compassWorld }, deviceAt = { PoseWorld.device((it / frame).toInt()) })
        val h = PoseHandoff(geoSensor)
        var kind = PoseKind.SENSOR
        for (i in 0 until 100) {
            val s = arSample(i * frame, deviceIndex = i)
            // Geospatial reports the camera in EUS: Ry(t) * arCamera for the true yaw t.
            val arCamera = PoseWorld.mul(PoseWorld.device(i), PoseWorld.rz(Math.toRadians(90.0)))
            arCamera.copyInto(s.cameraPose)
            PoseWorld.mul(PoseWorld.ry(Math.toRadians(truth)), arCamera).copyInto(s.geospatialEus)
            s.geospatialValid = true
            s.geospatialYawAccuracyDegrees = 4f
            h.onSample(s, i * frame, consented = true, zoomRatio = 1f)
            kind = h.kind
        }
        assertEquals(PoseKind.ARCORE, kind)
        val m = FloatArray(16)
        assertTrue(h.renderMatrix(99 * frame, m))
        val expected = PoseWorld.as4x4(PoseWorld.truth(truth, PoseWorld.device(99)))
        for (k in 0 until 16) assertEquals("element $k", expected[k], m[k], 5e-3f)
    }

    @Test
    fun `the matrix is a proper rotation`() {
        val i = runUntilArCore()
        handoff.renderMatrix(i * frame, out)
        // Rows are orthonormal.
        for (a in 0 until 3) for (b in 0 until 3) {
            val dot = (0 until 3).sumOf { (out[a * 4 + it] * out[b * 4 + it]).toDouble() }
            assertEquals(if (a == b) 1.0 else 0.0, dot, 1e-4)
        }
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(out[12], out[13], out[14], out[15]), 0f)
    }
}
