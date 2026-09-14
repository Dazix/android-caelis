package com.dazix.caelis.data

/**
 * Granular debugging and diagnostic preferences.
 * All flags default to false so production behavior is unaffected unless explicitly enabled.
 */
data class DebugPreferences(
    /** Enable constellation illustration alignment / artwork calibration debugger in Night Sky AR. */
    val enableNightSkyCalibration: Boolean = false,
    /** Enable constellation activation / focus region indicator in Night Sky AR. */
    val enableNightSkyFocusRegion: Boolean = false,
    /** Display real-time terrain horizon metrics (Auto-Range km and active DEM tile count) in AR views. */
    val showTerrainMetrics: Boolean = false,
) {
    /** True if any debug or diagnostic feature is currently turned on. */
    val anyEnabled: Boolean
        get() = enableNightSkyCalibration || enableNightSkyFocusRegion || showTerrainMetrics
}
