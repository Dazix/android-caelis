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
}
