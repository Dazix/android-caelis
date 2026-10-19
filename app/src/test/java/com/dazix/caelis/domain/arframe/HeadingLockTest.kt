package com.dazix.caelis.domain.arframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingLockTest {

    @Test
    fun `unlocked, nothing is corrected and the plain rotation vector is drawn`() {
        val lock = HeadingLock()
        lock.addCompass(14f)
        lock.addCompass(-30f)
        assertEquals(0f, lock.correctionDegrees(), 0f)
    }

    @Test
    fun `locking does not jump the overlay`() {
        val lock = HeadingLock()
        lock.addCompass(14f)
        lock.lock()
        assertTrue(lock.isLocked)
        assertEquals(0f, lock.correctionDegrees(), 0f)
    }

    @Test
    fun `a lock cancels a compass that swings with direction`() {
        val lock = HeadingLock()
        lock.addCompass(14f)
        lock.lock()
        // Turning towards the window frame bends the field by 20 degrees: the rotation vector's
        // heading moves by -20 and the correction by +20, so the drawn heading stays on the gyro.
        lock.addCompass(-6f)
        assertEquals(20f, lock.correctionDegrees(), 1e-4f)
        assertEquals(HeadingState.LOCKED, lock.state(0))
    }

    @Test
    fun `the correction takes the short way round`() {
        val lock = HeadingLock()
        lock.addCompass(175f)
        lock.lock()
        lock.addCompass(-175f)
        assertEquals(-10f, lock.correctionDegrees(), 1e-3f)
    }

    @Test
    fun `aligning again while locked keeps the original reference`() {
        val lock = HeadingLock()
        lock.addCompass(10f)
        lock.lock()
        lock.addCompass(4f)
        lock.lock()
        assertEquals(6f, lock.correctionDegrees(), 1e-4f)
    }

    @Test
    fun `there is nothing to lock before the first compass sample`() {
        val lock = HeadingLock()
        lock.lock()
        assertFalse(lock.isLocked)
        lock.addCompass(5f)
        assertEquals(0f, lock.correctionDegrees(), 0f)
    }

    @Test
    fun `a reset goes back to the compass`() {
        val lock = HeadingLock()
        lock.addCompass(14f)
        lock.lock()
        lock.addCompass(0f)
        lock.reset()
        assertFalse(lock.isLocked)
        assertFalse(lock.hasHeading)
        assertEquals(0f, lock.correctionDegrees(), 0f)
    }

    @Test
    fun `state reports the compass accuracy while unlocked`() {
        val lock = HeadingLock()
        assertEquals(HeadingState.UNRELIABLE, lock.state(0))
        assertEquals(HeadingState.LOW, lock.state(1))
        assertEquals(HeadingState.OK, lock.state(2))
        assertEquals(HeadingState.OK, lock.state(3))
    }

    // Pixel 10 Pro: the rotation vector reported HIGH accuracy while its own heading estimate in
    // values[4] said ±30°, and on the first start the overlay stood about 180° out with no warning.
    @Test
    fun `a heading uncertainty beyond what a calibration can reach is unreliable whatever the accuracy says`() {
        val lock = HeadingLock()
        assertEquals(HeadingState.UNRELIABLE, lock.state(3, headingUncertaintyDegrees = 30f, correctableDegrees = 20f))
        assertEquals(HeadingState.OK, lock.state(3, headingUncertaintyDegrees = 20f, correctableDegrees = 20f))
        assertEquals(HeadingState.OK, lock.state(3, headingUncertaintyDegrees = 5f, correctableDegrees = 20f))
    }

    @Test
    fun `an unreported uncertainty leaves the accuracy to decide`() {
        val lock = HeadingLock()
        assertEquals(HeadingState.OK, lock.state(3, headingUncertaintyDegrees = Float.NaN, correctableDegrees = 20f))
        assertEquals(HeadingState.LOW, lock.state(1, headingUncertaintyDegrees = Float.NaN, correctableDegrees = 20f))
    }

    @Test
    fun `a lock outranks the uncertainty in the state, not in the compass's own state`() {
        val lock = HeadingLock()
        lock.addCompass(0f)
        lock.lock()
        assertEquals(HeadingState.LOCKED, lock.state(3, headingUncertaintyDegrees = 90f, correctableDegrees = 20f))
        assertEquals(
            HeadingState.UNRELIABLE,
            HeadingLock.compassState(3, headingUncertaintyDegrees = 90f, correctableDegrees = 20f),
        )
    }

    @Test
    fun `a lock before the game vector settles does not cancel its convergence`() {
        // The device log: locked 10 s after start while the game vector was still converging, then
        // ψ moved by 5.6 degrees and the lock turned the overlay with it.
        val lock = HeadingLock()
        lock.addCompass(0f, gameVectorSettled = false)
        lock.lock()
        assertTrue(lock.isLocked)
        assertFalse(lock.isHolding)
        lock.addCompass(-3f, gameVectorSettled = false)
        assertEquals("still on the compass the alignment was measured against", 0f, lock.correctionDegrees(), 0f)
        lock.addCompass(-5.6f, gameVectorSettled = true)
        assertTrue(lock.isHolding)
        assertEquals("the hold starts where ψ settled: no jump", 0f, lock.correctionDegrees(), 0f)
        lock.addCompass(-25.6f, gameVectorSettled = true)
        assertEquals(20f, lock.correctionDegrees(), 1e-4f)
        assertEquals(HeadingState.LOCKED, lock.state(0))
    }

    @Test
    fun `a lock after the game vector settled holds at once`() {
        val lock = HeadingLock()
        lock.addCompass(7f, gameVectorSettled = true)
        lock.lock()
        assertTrue(lock.isHolding)
        lock.addCompass(2f, gameVectorSettled = true)
        assertEquals(5f, lock.correctionDegrees(), 1e-4f)
    }

    @Test
    fun `a reset drops a pending lock`() {
        val lock = HeadingLock()
        lock.addCompass(0f, gameVectorSettled = false)
        lock.lock()
        lock.reset()
        lock.addCompass(4f, gameVectorSettled = true)
        assertFalse(lock.isLocked)
        assertFalse(lock.isHolding)
        assertEquals(0f, lock.correctionDegrees(), 0f)
    }

    @Test
    fun `the uncertainty is read from values 4 in radians`() {
        assertEquals(29.79f, HeadingLock.headingUncertaintyDegrees(floatArrayOf(0f, 0f, 0f, 1f, 0.52f)), 0.01f)
        assertEquals(0f, HeadingLock.headingUncertaintyDegrees(floatArrayOf(0f, 0f, 0f, 1f, 0f)), 0f)
    }

    @Test
    fun `an absent or unavailable uncertainty is NaN`() {
        assertTrue(HeadingLock.headingUncertaintyDegrees(floatArrayOf(0f, 0f, 0f, 1f)).isNaN())
        assertTrue(HeadingLock.headingUncertaintyDegrees(floatArrayOf(0f, 0f, 0f)).isNaN())
        assertTrue(HeadingLock.headingUncertaintyDegrees(floatArrayOf(0f, 0f, 0f, 1f, -1f)).isNaN())
        assertTrue(HeadingLock.headingUncertaintyDegrees(floatArrayOf(0f, 0f, 0f, 1f, Float.NaN)).isNaN())
    }
}
