package com.dazix.intervalometer.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dazix.intervalometer.domain.RampCurve
import com.dazix.intervalometer.domain.RampDomain
import com.dazix.intervalometer.domain.RampSpec
import com.dazix.intervalometer.domain.SessionConfig
import com.dazix.intervalometer.domain.StopConditions
import com.dazix.intervalometer.domain.TapTarget
import com.dazix.intervalometer.domain.TriggerMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.intervalometerPreferences by preferencesDataStore(name = "intervalometer")

class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.intervalometerPreferences

    /**
     * For the long-lived flows the UI collects: a read error there must not tear the collector down, so
     * it degrades to "nothing stored". Deliberately *not* used by the one-shot reads below.
     */
    private val preferences: Flow<Preferences> =
        dataStore.data.catch { emit(emptyPreferences()) }

    /**
     * For a single read whose caller distinguishes "nothing stored" from "could not read".
     *
     * The calibration readers are exactly that caller: reporting an unreadable store as "this app has
     * no calibration" blocks Start and, before the mirrored copy was made read-only, wrote the miss
     * back over the config. Swallowing the failure here made the `storeReadable` guard in
     * `CalibrationResolution` unreachable — every failure arrived as a successful read of nothing.
     */
    private suspend fun currentPreferences(): Preferences = dataStore.data.first()

    val lastConfig: Flow<SessionConfig?> = preferences.map { SettingsMapping.readConfig(it) }

    val advanced: Flow<AdvancedSettings> = preferences.map { SettingsMapping.readAdvanced(it) }

    val sunArKeepScreenOn: Flow<Boolean> = preferences.map { SettingsMapping.readSunArKeepScreenOn(it) }

    val sunArRedVisionMode: Flow<Boolean> = preferences.map { SettingsMapping.readSunArRedVisionMode(it) }

    val sunArAzimuthOffset: Flow<Float> = preferences.map { SettingsMapping.readSunArAzimuthOffset(it) }

    val nightSkyKeepScreenOn: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyKeepScreenOn(it) }

    val nightSkyRedVisionMode: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyRedVisionMode(it) }

    val nightSkyAzimuthOffset: Flow<Float> = preferences.map { SettingsMapping.readNightSkyAzimuthOffset(it) }

    val nightSkyOpaqueBackground: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyOpaqueBackground(it) }

    val nightSkyMilkyWayVisible: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyMilkyWayVisible(it) }

    val nightSkyAtmosphereVisible: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyAtmosphereVisible(it) }

    val nightSkyConstellationsVisible: Flow<Boolean> = preferences.map { SettingsMapping.readNightSkyConstellationsVisible(it) }

    suspend fun saveSunArKeepScreenOn(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeSunArKeepScreenOn(it, enabled) }
    }

    suspend fun saveSunArRedVisionMode(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeSunArRedVisionMode(it, enabled) }
    }

    suspend fun saveSunArAzimuthOffset(offsetDeg: Float) {
        dataStore.edit { SettingsMapping.writeSunArAzimuthOffset(it, offsetDeg) }
    }

    suspend fun saveNightSkyKeepScreenOn(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyKeepScreenOn(it, enabled) }
    }

    suspend fun saveNightSkyRedVisionMode(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyRedVisionMode(it, enabled) }
    }

    suspend fun saveNightSkyAzimuthOffset(offsetDeg: Float) {
        dataStore.edit { SettingsMapping.writeNightSkyAzimuthOffset(it, offsetDeg) }
    }

    suspend fun saveNightSkyOpaqueBackground(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyOpaqueBackground(it, enabled) }
    }

    suspend fun saveNightSkyMilkyWayVisible(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyMilkyWayVisible(it, enabled) }
    }

    suspend fun saveNightSkyAtmosphereVisible(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyAtmosphereVisible(it, enabled) }
    }

    suspend fun saveNightSkyConstellationsVisible(enabled: Boolean) {
        dataStore.edit { SettingsMapping.writeNightSkyConstellationsVisible(it, enabled) }
    }

    suspend fun saveConfig(config: SessionConfig) {
        dataStore.edit { SettingsMapping.writeConfig(it, config) }
    }

    suspend fun saveTarget(packageName: String, orientation: Int, target: TapTarget) {
        dataStore.edit { SettingsMapping.writeTarget(it, packageName, orientation, target) }
    }

    /** Exactly the slot [orientation] was stored in, or null. See [anyTarget] for the usable read. */
    suspend fun target(packageName: String, orientation: Int): TapTarget? =
        SettingsMapping.readTarget(currentPreferences(), packageName, orientation)

    /**
     * The calibration to use for [packageName] with the display at [preferredRotation], taken from
     * whichever slot has one — see [SettingsMapping.readAnyTarget].
     *
     * This is what every reader wants. Asking for one rotation and giving up is what made a
     * calibration captured in landscape read as "not calibrated" the moment the phone was turned
     * upright, which blocked Start and then wrote the miss back over the session config.
     */
    suspend fun anyTarget(packageName: String, preferredRotation: Int): TapTarget? =
        SettingsMapping.readAnyTarget(currentPreferences(), packageName, preferredRotation)

    suspend fun saveAdvanced(settings: AdvancedSettings) {
        dataStore.edit { SettingsMapping.writeAdvanced(it, settings) }
    }
}

/**
 * The single place where domain models meet primitive preference keys. Readers are total: a partially
 * written or corrupted record degrades to `null` (config, target) or to defaults (advanced) rather
 * than throwing.
 *
 * `internal` rather than private so the round-trips can be unit-tested against an in-memory
 * `MutablePreferences`, which is the only part of this file that has no device dependency.
 */
internal object SettingsMapping {

    private const val MODE_TIMELAPSE = "timelapse"
    private const val MODE_PERIODIC_TAP = "periodic_tap"

    private val MODE = stringPreferencesKey("mode")
    private val MODE_PACKAGE = stringPreferencesKey("mode_package")
    private val INTERVAL_MS = longPreferencesKey("interval_ms")
    private val START_DELAY_MS = longPreferencesKey("start_delay_ms")
    private val VERIFY_CAPTURES = booleanPreferencesKey("verify_captures")
    private val MIN_INTERVAL_FLOOR_MS = longPreferencesKey("min_interval_floor_ms")

    private val RAMP_CURVE = stringPreferencesKey("ramp_curve")
    private val RAMP_END_INTERVAL_MS = longPreferencesKey("ramp_end_interval_ms")
    private val RAMP_DOMAIN = stringPreferencesKey("ramp_domain")
    private val RAMP_OVER_SHOTS = intPreferencesKey("ramp_over_shots")
    private val RAMP_OVER_DURATION_MS = longPreferencesKey("ramp_over_duration_ms")

    private val STOP_MAX_SHOTS = intPreferencesKey("stop_max_shots")
    private val STOP_MAX_DURATION_MS = longPreferencesKey("stop_max_duration_ms")
    private val STOP_MIN_BATTERY_PERCENT = intPreferencesKey("stop_min_battery_percent")
    private val STOP_MIN_FREE_BYTES = longPreferencesKey("stop_min_free_bytes")

    private val CONFIG_TARGET_X = floatPreferencesKey("config_target_x")
    private val CONFIG_TARGET_Y = floatPreferencesKey("config_target_y")
    private val CONFIG_TARGET_W = floatPreferencesKey("config_target_w")
    private val CONFIG_TARGET_H = floatPreferencesKey("config_target_h")
    private val CONFIG_TARGET_JITTER = booleanPreferencesKey("config_target_jitter")

    private val ADV_LAUNCH_CAMERA_MODE = stringPreferencesKey("advanced_launch_camera_mode")
    private val ADV_ADAPTIVE_LONG_EXPOSURE_WAIT = booleanPreferencesKey("advanced_adaptive_long_exposure_wait")
    private val ADV_ENSURE_SELF_TIMER_OFF = booleanPreferencesKey("advanced_ensure_self_timer_off")
    private val ADV_DISMISS_POST_CAPTURE_UI = booleanPreferencesKey("advanced_dismiss_post_capture_ui")
    private val ADV_KEEP_SCREEN_ON = booleanPreferencesKey("advanced_keep_screen_on")
    private val ADV_DIM_OVERLAY = booleanPreferencesKey("advanced_dim_overlay")
    private val SUN_AR_KEEP_SCREEN_ON = booleanPreferencesKey("sun_ar_keep_screen_on")
    private val SUN_AR_RED_VISION_MODE = booleanPreferencesKey("sun_ar_red_vision_mode")
    private val SUN_AR_AZIMUTH_OFFSET = floatPreferencesKey("sun_ar_azimuth_offset")
    private val NIGHT_SKY_KEEP_SCREEN_ON = booleanPreferencesKey("night_sky_keep_screen_on")
    private val NIGHT_SKY_RED_VISION_MODE = booleanPreferencesKey("night_sky_red_vision_mode")
    private val NIGHT_SKY_AZIMUTH_OFFSET = floatPreferencesKey("night_sky_azimuth_offset")
    private val NIGHT_SKY_OPAQUE_BACKGROUND = booleanPreferencesKey("night_sky_opaque_background")
    private val NIGHT_SKY_MILKY_WAY_VISIBLE = booleanPreferencesKey("night_sky_milky_way_visible")
    private val NIGHT_SKY_ATMOSPHERE_VISIBLE = booleanPreferencesKey("night_sky_atmosphere_visible")
    private val NIGHT_SKY_CONSTELLATIONS_VISIBLE = booleanPreferencesKey("night_sky_constellations_visible")

    fun writeConfig(prefs: MutablePreferences, config: SessionConfig) {
        prefs[MODE] = when (config.mode) {
            is TriggerMode.Timelapse -> MODE_TIMELAPSE
            is TriggerMode.PeriodicTap -> MODE_PERIODIC_TAP
        }
        prefs.put(MODE_PACKAGE, config.mode.targetPackage)
        prefs[INTERVAL_MS] = config.intervalMs
        prefs[START_DELAY_MS] = config.startDelayMs
        prefs[VERIFY_CAPTURES] = config.verifyCaptures
        prefs[MIN_INTERVAL_FLOOR_MS] = config.minIntervalFloorMs

        val ramp = config.ramp
        prefs.put(RAMP_CURVE, ramp?.curve?.name)
        prefs.put(RAMP_END_INTERVAL_MS, ramp?.endIntervalMs)
        prefs.put(RAMP_DOMAIN, ramp?.domain?.name)
        prefs.put(RAMP_OVER_SHOTS, ramp?.overShots)
        prefs.put(RAMP_OVER_DURATION_MS, ramp?.overDurationMs)

        prefs.put(STOP_MAX_SHOTS, config.stop.maxShots)
        prefs.put(STOP_MAX_DURATION_MS, config.stop.maxDurationMs)
        prefs.put(STOP_MIN_BATTERY_PERCENT, config.stop.minBatteryPercent)
        prefs.put(STOP_MIN_FREE_BYTES, config.stop.minFreeBytes)

        val target = config.target
        prefs.put(CONFIG_TARGET_X, target?.xFraction)
        prefs.put(CONFIG_TARGET_Y, target?.yFraction)
        prefs.put(CONFIG_TARGET_W, target?.widthFraction)
        prefs.put(CONFIG_TARGET_H, target?.heightFraction)
        prefs.put(CONFIG_TARGET_JITTER, target?.jitter)
    }

    fun readConfig(prefs: Preferences): SessionConfig? = runCatching {
        val pkg = prefs[MODE_PACKAGE]
        val mode = when (prefs[MODE]) {
            MODE_TIMELAPSE -> pkg?.let { TriggerMode.Timelapse(it) }
            MODE_PERIODIC_TAP -> TriggerMode.PeriodicTap(pkg)
            else -> null
        } ?: return@runCatching null
        val interval = prefs[INTERVAL_MS]?.takeIf { it > 0L } ?: return@runCatching null
        SessionConfig(
            mode = mode,
            intervalMs = interval,
            startDelayMs = prefs[START_DELAY_MS] ?: 0L,
            ramp = readRamp(prefs),
            stop = StopConditions(
                maxShots = prefs[STOP_MAX_SHOTS],
                maxDurationMs = prefs[STOP_MAX_DURATION_MS],
                minBatteryPercent = prefs[STOP_MIN_BATTERY_PERCENT],
                minFreeBytes = prefs[STOP_MIN_FREE_BYTES],
            ),
            target = readConfigTarget(prefs),
            verifyCaptures = prefs[VERIFY_CAPTURES] ?: false,
            minIntervalFloorMs = prefs[MIN_INTERVAL_FLOOR_MS] ?: 0L,
        )
    }.getOrNull()

    fun writeTarget(prefs: MutablePreferences, packageName: String, orientation: Int, target: TapTarget) {
        val p = targetPrefix(packageName, orientation)
        prefs[floatPreferencesKey(p + "x")] = target.xFraction
        prefs[floatPreferencesKey(p + "y")] = target.yFraction
        prefs[floatPreferencesKey(p + "w")] = target.widthFraction
        prefs[floatPreferencesKey(p + "h")] = target.heightFraction
        prefs[booleanPreferencesKey(p + "jitter")] = target.jitter
    }

    fun readTarget(prefs: Preferences, packageName: String, orientation: Int): TapTarget? = runCatching {
        val p = targetPrefix(packageName, orientation)
        val x = prefs[floatPreferencesKey(p + "x")] ?: return@runCatching null
        val y = prefs[floatPreferencesKey(p + "y")] ?: return@runCatching null
        TapTarget(
            xFraction = x,
            yFraction = y,
            widthFraction = prefs[floatPreferencesKey(p + "w")] ?: 0f,
            heightFraction = prefs[floatPreferencesKey(p + "h")] ?: 0f,
            jitter = prefs[booleanPreferencesKey(p + "jitter")] ?: false,
        )
    }.getOrNull()

    /**
     * Every calibration stored for [packageName], keyed by the display rotation it was captured in
     * and ordered by that rotation. A slot that is half written is simply absent, exactly as in
     * [readTarget].
     */
    fun readTargetSlots(prefs: Preferences, packageName: String): Map<Int, TapTarget> {
        val prefix = targetPrefix(packageName)
        return prefs.asMap().keys.asSequence()
            .map { it.name }
            .filter { it.startsWith(prefix) }
            // "target_<pkg>_<rotation>_x" -> <rotation>. A package whose *name* ends in "_<digits>"
            // can produce a bogus rotation here, which costs nothing: the slot is then read back
            // through readTarget, which finds no keys for it and drops it.
            .mapNotNull { it.removePrefix(prefix).substringBefore('_').toIntOrNull() }
            .distinct()
            .sorted()
            .mapNotNull { rotation -> readTarget(prefs, packageName, rotation)?.let { rotation to it } }
            .toMap()
    }

    /**
     * The calibration to use for [packageName] with the display at [preferredRotation]: that slot if
     * it has one, else the natural-orientation slot, else any slot at all.
     *
     * Slots exist because the shutter is rarely at the mirrored spot after a quarter turn, so the
     * rotation the user calibrated in is the *best* answer — but every stored fraction is already in
     * natural display space (see [com.dazix.intervalometer.service.DisplayGeometry]), so every other
     * slot is a usable answer too. Reading only the current rotation and the natural one meant a
     * calibration captured in landscape vanished as soon as the phone was held upright: Home said
     * "not calibrated", Start was blocked, and the read-back miss was persisted over the config.
     */
    fun readAnyTarget(
        prefs: Preferences,
        packageName: String,
        preferredRotation: Int,
    ): TapTarget? {
        val slots = readTargetSlots(prefs, packageName)
        return slots[preferredRotation] ?: slots[NATURAL_ROTATION] ?: slots.values.firstOrNull()
    }

    fun writeAdvanced(prefs: MutablePreferences, settings: AdvancedSettings) {
        prefs.put(ADV_LAUNCH_CAMERA_MODE, settings.launchCameraMode)
        prefs[ADV_ADAPTIVE_LONG_EXPOSURE_WAIT] = settings.adaptiveLongExposureWait
        prefs[ADV_ENSURE_SELF_TIMER_OFF] = settings.ensureSelfTimerOff
        prefs[ADV_DISMISS_POST_CAPTURE_UI] = settings.dismissPostCaptureUi
        prefs[ADV_KEEP_SCREEN_ON] = settings.keepScreenOn
        prefs[ADV_DIM_OVERLAY] = settings.dimOverlay
    }

    fun readAdvanced(prefs: Preferences): AdvancedSettings = runCatching {
        AdvancedSettings(
            launchCameraMode = prefs[ADV_LAUNCH_CAMERA_MODE],
            adaptiveLongExposureWait = prefs[ADV_ADAPTIVE_LONG_EXPOSURE_WAIT] ?: false,
            ensureSelfTimerOff = prefs[ADV_ENSURE_SELF_TIMER_OFF] ?: false,
            dismissPostCaptureUi = prefs[ADV_DISMISS_POST_CAPTURE_UI] ?: false,
            // Absent key -> the shared default, which for these two is whatever
            // overlay.OverlaySettings defaults to: the panel is the thing that obeys them, and a
            // record from before they existed must not read as a *different* setting from a fresh
            // install. Hence AdvancedSettings()'s own values rather than a literal here.
            keepScreenOn = prefs[ADV_KEEP_SCREEN_ON] ?: AdvancedSettings().keepScreenOn,
            dimOverlay = prefs[ADV_DIM_OVERLAY] ?: AdvancedSettings().dimOverlay,
        )
    }.getOrElse { AdvancedSettings() }

    private fun readRamp(prefs: Preferences): RampSpec? {
        val curve = RampCurve.entries.firstOrNull { it.name == prefs[RAMP_CURVE] } ?: return null
        val domain = RampDomain.entries.firstOrNull { it.name == prefs[RAMP_DOMAIN] } ?: return null
        val end = prefs[RAMP_END_INTERVAL_MS]?.takeIf { it > 0L } ?: return null
        return RampSpec(
            curve = curve,
            endIntervalMs = end,
            domain = domain,
            overShots = prefs[RAMP_OVER_SHOTS],
            overDurationMs = prefs[RAMP_OVER_DURATION_MS],
        )
    }

    private fun readConfigTarget(prefs: Preferences): TapTarget? {
        val x = prefs[CONFIG_TARGET_X] ?: return null
        val y = prefs[CONFIG_TARGET_Y] ?: return null
        return TapTarget(
            xFraction = x,
            yFraction = y,
            widthFraction = prefs[CONFIG_TARGET_W] ?: 0f,
            heightFraction = prefs[CONFIG_TARGET_H] ?: 0f,
            jitter = prefs[CONFIG_TARGET_JITTER] ?: false,
        )
    }

    /** Common prefix of every calibration key of [packageName], whatever rotation it was stored in. */
    private fun targetPrefix(packageName: String) = "target_${packageName}_"

    private fun targetPrefix(packageName: String, orientation: Int) =
        targetPrefix(packageName) + "${orientation}_"

    /**
     * `Surface.ROTATION_0`, spelled out so this file stays free of Android classes and testable on
     * the JVM. The natural orientation is the fallback slot because it is the one a phone is most
     * often calibrated in.
     */
    private const val NATURAL_ROTATION = 0

    fun readSunArKeepScreenOn(prefs: Preferences): Boolean =
        prefs[SUN_AR_KEEP_SCREEN_ON] ?: false

    fun writeSunArKeepScreenOn(prefs: MutablePreferences, enabled: Boolean) {
        prefs[SUN_AR_KEEP_SCREEN_ON] = enabled
    }

    fun readSunArRedVisionMode(prefs: Preferences): Boolean =
        prefs[SUN_AR_RED_VISION_MODE] ?: false

    fun writeSunArRedVisionMode(prefs: MutablePreferences, enabled: Boolean) {
        prefs[SUN_AR_RED_VISION_MODE] = enabled
    }

    fun readSunArAzimuthOffset(prefs: Preferences): Float =
        prefs[SUN_AR_AZIMUTH_OFFSET] ?: 0.0f

    fun writeSunArAzimuthOffset(prefs: MutablePreferences, offsetDeg: Float) {
        prefs[SUN_AR_AZIMUTH_OFFSET] = offsetDeg
    }

    fun readNightSkyKeepScreenOn(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_KEEP_SCREEN_ON] ?: false

    fun writeNightSkyKeepScreenOn(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_KEEP_SCREEN_ON] = enabled
    }

    fun readNightSkyRedVisionMode(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_RED_VISION_MODE] ?: true

    fun writeNightSkyRedVisionMode(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_RED_VISION_MODE] = enabled
    }

    fun readNightSkyAzimuthOffset(prefs: Preferences): Float =
        prefs[NIGHT_SKY_AZIMUTH_OFFSET] ?: 0.0f

    fun writeNightSkyAzimuthOffset(prefs: MutablePreferences, offsetDeg: Float) {
        prefs[NIGHT_SKY_AZIMUTH_OFFSET] = offsetDeg
    }

    fun readNightSkyOpaqueBackground(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_OPAQUE_BACKGROUND] ?: false

    fun writeNightSkyOpaqueBackground(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_OPAQUE_BACKGROUND] = enabled
    }

    fun readNightSkyMilkyWayVisible(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_MILKY_WAY_VISIBLE] ?: true

    fun writeNightSkyMilkyWayVisible(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_MILKY_WAY_VISIBLE] = enabled
    }

    fun readNightSkyAtmosphereVisible(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_ATMOSPHERE_VISIBLE] ?: true

    fun writeNightSkyAtmosphereVisible(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_ATMOSPHERE_VISIBLE] = enabled
    }

    fun readNightSkyConstellationsVisible(prefs: Preferences): Boolean =
        prefs[NIGHT_SKY_CONSTELLATIONS_VISIBLE] ?: true

    fun writeNightSkyConstellationsVisible(prefs: MutablePreferences, enabled: Boolean) {
        prefs[NIGHT_SKY_CONSTELLATIONS_VISIBLE] = enabled
    }

    private fun <T : Any> MutablePreferences.put(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else set(key, value)
    }
}
