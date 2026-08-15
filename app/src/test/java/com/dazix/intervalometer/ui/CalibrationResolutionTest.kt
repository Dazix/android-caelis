package com.dazix.intervalometer.ui

import com.dazix.intervalometer.domain.TapTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The rule these cover is the one that made a freshly chosen camera app report "calibrated" and then
 * tap the previous app's shutter position.
 */
class CalibrationResolutionTest {

    private val oldApp = TapTarget(xFraction = 0.5f, yFraction = 0.9f)
    private val newApp = TapTarget(xFraction = 0.8f, yFraction = 0.5f)

    @Test
    fun `a package with nothing stored is not calibrated even when a target is in memory`() {
        assertNull(
            CalibrationResolution.resolve(
                targetPackage = "com.example.newcamera",
                stored = null,
                inMemory = oldApp,
            )
        )
    }

    @Test
    fun `the stored calibration of the chosen package wins over the one in memory`() {
        assertEquals(
            newApp,
            CalibrationResolution.resolve(
                targetPackage = "com.example.newcamera",
                stored = newApp,
                inMemory = oldApp,
            )
        )
    }

    @Test
    fun `without a target app the in-memory calibration is the only one there is`() {
        assertSame(
            oldApp,
            CalibrationResolution.resolve(targetPackage = null, stored = null, inMemory = oldApp),
        )
    }

    @Test
    fun `without a target app and without a calibration nothing is reported`() {
        assertNull(CalibrationResolution.resolve(targetPackage = null, stored = null, inMemory = null))
    }

    /**
     * A read that never reached the store says nothing about what is stored. Treating its empty answer
     * as "this package has none" is how a calibration the user had just made was reported as missing
     * and then persisted away — the very shape of the rotation defect, one layer down.
     */
    @Test
    fun `a store that could not be read keeps the calibration on screen`() {
        assertSame(
            newApp,
            CalibrationResolution.resolve(
                targetPackage = "com.example.newcamera",
                stored = null,
                inMemory = newApp,
                storeReadable = false,
            ),
        )
    }

    @Test
    fun `an answered read that came back empty is the only thing that clears a calibration`() {
        assertNull(
            CalibrationResolution.resolve(
                targetPackage = "com.example.newcamera",
                stored = null,
                inMemory = newApp,
                storeReadable = true,
            ),
        )
    }
}
