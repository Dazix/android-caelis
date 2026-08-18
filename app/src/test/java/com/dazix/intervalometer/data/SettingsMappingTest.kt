package com.dazix.intervalometer.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.dazix.intervalometer.domain.RampCurve
import com.dazix.intervalometer.domain.RampDomain
import com.dazix.intervalometer.domain.RampSpec
import com.dazix.intervalometer.domain.SessionConfig
import com.dazix.intervalometer.domain.StopConditions
import com.dazix.intervalometer.domain.TapTarget
import com.dazix.intervalometer.domain.TriggerMode
import com.dazix.intervalometer.overlay.OverlayOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The preference mapping is where a setting silently stops being saved. `MutablePreferences` is plain
 * JVM, so the round-trips can be held down here rather than by noticing on a device that a toggle
 * forgot itself.
 */
class SettingsMappingTest {

    // Surface.ROTATION_*, spelled out: this suite touches no Android class.
    private val ROTATION_0 = 0
    private val ROTATION_90 = 1
    private val ROTATION_180 = 2
    private val ROTATION_270 = 3

    private val PKG = "com.example.cam"

    /** Fractions are stored in natural display space, so both are valid in either rotation. */
    private val portrait = TapTarget(xFraction = 0.5f, yFraction = 0.88f)
    private val landscape = TapTarget(xFraction = 0.88f, yFraction = 0.5f, jitter = true)

    @Test
    fun `every advanced flag survives a round trip`() {
        val settings = AdvancedSettings(
            launchCameraMode = "Night Sight",
            adaptiveLongExposureWait = true,
            ensureSelfTimerOff = true,
            dismissPostCaptureUi = true,
            keepScreenOn = true,
            dimOverlay = true,
        )

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeAdvanced(prefs, settings)

        assertEquals(settings, SettingsMapping.readAdvanced(prefs))
    }

    /**
     * The flags added for the overlay did not exist in earlier installs, so their keys are missing
     * from every stored record. Missing must read as *the same thing a fresh install reads*, not as a
     * third state: the floating panel obeys `OverlaySettings`, whose keep-screen-on defaults to on,
     * and an upgraded install that silently read it as off would pause at the first display timeout
     * while a fresh one kept shooting. Everything else stays untouched.
     */
    @Test
    fun `a record written before the new flags existed reads as the shared defaults`() {
        val older = mutablePreferencesOf()
        SettingsMapping.writeAdvanced(older, AdvancedSettings(adaptiveLongExposureWait = true))
        older.removeByName("advanced_keep_screen_on", "advanced_dim_overlay")

        val restored = SettingsMapping.readAdvanced(older)

        assertEquals(AdvancedSettings().keepScreenOn, restored.keepScreenOn)
        assertEquals(AdvancedSettings().dimOverlay, restored.dimOverlay)
        assertEquals(true, restored.adaptiveLongExposureWait)
    }

    /**
     * The two mirrored flags must default to exactly what the panel defaults to, or the advanced
     * screen shows one thing on a fresh install and the panel does another.
     */
    @Test
    fun `the mirrored overlay flags share the panel's defaults`() {
        assertEquals(OverlayOptions().keepScreenOn, AdvancedSettings().keepScreenOn)
        assertEquals(OverlayOptions().dimPanel, AdvancedSettings().dimOverlay)
    }

    /** Explicitly off still round-trips: the new default must not become a floor. */
    @Test
    fun `keep screen on can be stored as off`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeAdvanced(prefs, AdvancedSettings(keepScreenOn = false))

        assertFalse(SettingsMapping.readAdvanced(prefs).keepScreenOn)
    }

    @Test
    fun `an empty store reads as the default advanced settings`() {
        assertEquals(AdvancedSettings(), SettingsMapping.readAdvanced(emptyPreferences()))
    }

    @Test
    fun `a fully specified config survives a round trip`() {
        val config = SessionConfig(
            mode = TriggerMode.Timelapse("com.google.android.GoogleCamera"),
            intervalMs = 30_000L,
            startDelayMs = 5_000L,
            ramp = RampSpec(RampCurve.EXPONENTIAL, 90_000L, RampDomain.ELAPSED_TIME, overDurationMs = 600_000L),
            stop = StopConditions(maxShots = 300, maxDurationMs = 3_600_000L, minBatteryPercent = 20, minFreeBytes = 1_000L),
            target = TapTarget(0.5f, 0.87f, 0.1f, 0.1f, jitter = true),
            verifyCaptures = true,
            minIntervalFloorMs = 1_200L,
        )

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeConfig(prefs, config)

        assertEquals(config, SettingsMapping.readConfig(prefs))
    }

    @Test
    fun `an empty or half written config reads as nothing to start from`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeConfig(
            prefs,
            SessionConfig(TriggerMode.Timelapse("com.example.cam"), 10_000L),
        )
        val withoutInterval = mutablePreferencesOf()
        SettingsMapping.writeConfig(
            withoutInterval,
            SessionConfig(TriggerMode.Timelapse("com.example.cam"), 10_000L),
        )
        withoutInterval.removeByName("interval_ms")

        assertNull(SettingsMapping.readConfig(emptyPreferences()))
        assertNull(SettingsMapping.readConfig(withoutInterval))
        assertEquals(10_000L, SettingsMapping.readConfig(prefs)?.intervalMs)
    }

    /** Writing a config with no ramp has to clear a ramp an earlier config left behind. */
    @Test
    fun `clearing an optional field removes it instead of leaving the old value`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeConfig(
            prefs,
            SessionConfig(
                mode = TriggerMode.Timelapse("com.example.cam"),
                intervalMs = 10_000L,
                ramp = RampSpec(RampCurve.LINEAR, 20_000L, RampDomain.SHOT_INDEX, overShots = 50),
                target = TapTarget(0.5f, 0.9f),
            ),
        )
        SettingsMapping.writeConfig(
            prefs,
            SessionConfig(TriggerMode.Timelapse("com.example.cam"), 10_000L),
        )

        val restored = SettingsMapping.readConfig(prefs)

        assertNull(restored?.ramp)
        assertNull(restored?.target)
    }

    // ---- calibration slots -----------------------------------------------------------------------

    /**
     * The rotation the user calibrated in is the best slot, because the shutter is rarely at the
     * mirrored spot after a quarter turn.
     */
    @Test
    fun `the slot for the current rotation wins`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_0, portrait)
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_90, landscape)

        assertEquals(landscape, SettingsMapping.readAnyTarget(prefs, PKG, ROTATION_90))
        assertEquals(portrait, SettingsMapping.readAnyTarget(prefs, PKG, ROTATION_0))
    }

    /**
     * The defect: calibrate in landscape, turn the phone upright, and a read of the current rotation
     * plus the natural one found nothing — Home said "not calibrated", Start was blocked, and the miss
     * was written back over the config. Every stored fraction is already in natural display space, so
     * the landscape slot was usable the whole time.
     */
    @Test
    fun `a calibration made in one rotation is found from every other rotation`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_90, landscape)

        for (rotation in listOf(ROTATION_0, ROTATION_90, ROTATION_180, ROTATION_270)) {
            assertEquals(
                "no calibration found with the display at rotation $rotation",
                landscape,
                SettingsMapping.readAnyTarget(prefs, PKG, rotation),
            )
        }
    }

    /** With no slot for the current rotation, the natural one is preferred over the rest. */
    @Test
    fun `the natural rotation is the second choice, not just any slot`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_0, portrait)
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_270, landscape)

        assertEquals(portrait, SettingsMapping.readAnyTarget(prefs, PKG, ROTATION_90))
    }

    @Test
    fun `a package with no slot at all is still not calibrated`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_90, landscape)

        assertNull(SettingsMapping.readAnyTarget(prefs, "com.example.other", ROTATION_90))
        assertNull(SettingsMapping.readAnyTarget(emptyPreferences(), PKG, ROTATION_0))
    }

    /** A calibration belongs to one package; another app's slots must never stand in for it. */
    @Test
    fun `slots are listed per package`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_0, portrait)
        SettingsMapping.writeTarget(prefs, "com.example.other", ROTATION_90, landscape)

        assertEquals(mapOf(ROTATION_0 to portrait), SettingsMapping.readTargetSlots(prefs, PKG))
    }

    @Test
    fun `every stored rotation is listed, in rotation order`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_270, landscape)
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_0, portrait)

        assertEquals(
            listOf(ROTATION_0, ROTATION_270),
            SettingsMapping.readTargetSlots(prefs, PKG).keys.toList(),
        )
    }

    /**
     * A half-written slot is not a calibration, in the fallback exactly as in [SettingsMapping.readTarget]
     * — otherwise a torn write in one rotation would shadow a good calibration in another.
     */
    @Test
    fun `a half written slot is skipped in favour of a complete one`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_0, portrait)
        SettingsMapping.writeTarget(prefs, PKG, ROTATION_90, landscape)
        prefs.removeByName("target_${PKG}_0_y")

        assertEquals(landscape, SettingsMapping.readAnyTarget(prefs, PKG, ROTATION_0))
        assertEquals(mapOf(ROTATION_90 to landscape), SettingsMapping.readTargetSlots(prefs, PKG))
    }

    /** A package name that itself ends in an underscore and digits must not read as a slot. */
    @Test
    fun `a package whose name looks like a slot key is not confused with one`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeTarget(prefs, "com.example.cam_1", ROTATION_0, landscape)

        assertNull(SettingsMapping.readAnyTarget(prefs, "com.example.cam", ROTATION_0))
        assertEquals(
            landscape,
            SettingsMapping.readAnyTarget(prefs, "com.example.cam_1", ROTATION_90),
        )
    }

    @Test
    fun `sun ar keep screen on defaults to false on empty preferences`() {
        assertFalse(SettingsMapping.readSunArKeepScreenOn(emptyPreferences()))
    }

    @Test
    fun `sun ar keep screen on survives round trip when true and false`() {
        val prefs = mutablePreferencesOf()
        SettingsMapping.writeSunArKeepScreenOn(prefs, true)
        assertEquals(true, SettingsMapping.readSunArKeepScreenOn(prefs))

        SettingsMapping.writeSunArKeepScreenOn(prefs, false)
        assertEquals(false, SettingsMapping.readSunArKeepScreenOn(prefs))
    }

    @Test
    fun `sun ar red vision mode defaults to false and survives round trip`() {
        assertFalse(SettingsMapping.readSunArRedVisionMode(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeSunArRedVisionMode(prefs, true)
        assertEquals(true, SettingsMapping.readSunArRedVisionMode(prefs))

        SettingsMapping.writeSunArRedVisionMode(prefs, false)
        assertEquals(false, SettingsMapping.readSunArRedVisionMode(prefs))
    }

    @Test
    fun `sun ar azimuth offset defaults to 0 and survives round trip`() {
        assertEquals(0.0f, SettingsMapping.readSunArAzimuthOffset(emptyPreferences()), 0.001f)

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeSunArAzimuthOffset(prefs, 15.5f)
        assertEquals(15.5f, SettingsMapping.readSunArAzimuthOffset(prefs), 0.001f)

        SettingsMapping.writeSunArAzimuthOffset(prefs, -8.25f)
        assertEquals(-8.25f, SettingsMapping.readSunArAzimuthOffset(prefs), 0.001f)
    }

    @Test
    fun `night sky keep screen on defaults to false and survives round trip`() {
        assertFalse(SettingsMapping.readNightSkyKeepScreenOn(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyKeepScreenOn(prefs, true)
        assertEquals(true, SettingsMapping.readNightSkyKeepScreenOn(prefs))

        SettingsMapping.writeNightSkyKeepScreenOn(prefs, false)
        assertEquals(false, SettingsMapping.readNightSkyKeepScreenOn(prefs))
    }

    @Test
    fun `night sky red vision mode defaults to true and survives round trip`() {
        assertEquals(true, SettingsMapping.readNightSkyRedVisionMode(emptyPreferences()))

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyRedVisionMode(prefs, false)
        assertEquals(false, SettingsMapping.readNightSkyRedVisionMode(prefs))

        SettingsMapping.writeNightSkyRedVisionMode(prefs, true)
        assertEquals(true, SettingsMapping.readNightSkyRedVisionMode(prefs))
    }

    @Test
    fun `night sky azimuth offset defaults to 0 and survives round trip`() {
        assertEquals(0.0f, SettingsMapping.readNightSkyAzimuthOffset(emptyPreferences()), 0.001f)

        val prefs = mutablePreferencesOf()
        SettingsMapping.writeNightSkyAzimuthOffset(prefs, 12.5f)
        assertEquals(12.5f, SettingsMapping.readNightSkyAzimuthOffset(prefs), 0.001f)

        SettingsMapping.writeNightSkyAzimuthOffset(prefs, -4.5f)
        assertEquals(-4.5f, SettingsMapping.readNightSkyAzimuthOffset(prefs), 0.001f)
    }

    /** Simulates a record written by a build that did not have these keys yet. */
    private fun MutablePreferences.removeByName(vararg names: String) {
        asMap().keys.filter { it.name in names }.toList().forEach {
            @Suppress("UNCHECKED_CAST")
            remove(it as Preferences.Key<Any>)
        }
    }
}
