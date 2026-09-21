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
    /** Physical touch target radius in dp for celestial body hit-testing in Night Sky AR. */
    val starTapRadiusDp: Int = DEFAULT_STAR_TAP_RADIUS_DP,
    /** Visualize celestial hit-test radius circle upon tapping in Night Sky AR. */
    val showTapRadiusIndicator: Boolean = false,
) {
    /** True if any debug or diagnostic feature is currently turned on. */
    val anyEnabled: Boolean
        get() = enableNightSkyCalibration ||
            enableNightSkyFocusRegion ||
            showTerrainMetrics ||
            showTapRadiusIndicator ||
            starTapRadiusDp != DEFAULT_STAR_TAP_RADIUS_DP

    companion object {
        const val DEFAULT_STAR_TAP_RADIUS_DP = 48
        const val MIN_STAR_TAP_RADIUS_DP = 16
        const val MAX_STAR_TAP_RADIUS_DP = 120
        const val STEP_STAR_TAP_RADIUS_DP = 4
    }
}
