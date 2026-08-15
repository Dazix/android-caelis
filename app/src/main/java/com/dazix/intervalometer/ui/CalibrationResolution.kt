package com.dazix.intervalometer.ui

import com.dazix.intervalometer.domain.TapTarget

/**
 * Decides which calibration the UI is allowed to call "the current one".
 *
 * A calibration is stored per target package and per display rotation, so it is only ever valid for
 * the package it was captured against: keeping the previous app's target when the user picks a new
 * app would report "calibrated" and then tap the old app's shutter position. The one case with no
 * stored slot at all is a session without a target app (periodic tap on whatever is on screen) —
 * there the calibration lives in the session config alone, so the in-memory value is the only one
 * there is.
 */
internal object CalibrationResolution {

    /**
     * @param targetPackage the package currently chosen in setup, `null` for "no target app".
     * @param stored what [com.dazix.intervalometer.data.SettingsStore] holds for [targetPackage],
     *   already resolved across rotation slots by the caller
     *   ([com.dazix.intervalometer.data.SettingsMapping.readAnyTarget]).
     * @param inMemory the calibration currently held in the setup state.
     * @param storeReadable whether the read actually reached the store. A read that *failed* says
     *   nothing about what is stored, so it must not be allowed to clear a calibration the user can
     *   see — only an answered read that came back empty means "this package has none".
     */
    fun resolve(
        targetPackage: String?,
        stored: TapTarget?,
        inMemory: TapTarget?,
        storeReadable: Boolean = true,
    ): TapTarget? = when {
        // Nothing was learned, so nothing changes: keeping what is on screen is the only answer that
        // cannot discard a calibration the user has just made.
        !storeReadable -> inMemory
        // No package means no slot to store one in: a periodic tap on whatever is on screen keeps its
        // calibration in the session config alone.
        targetPackage == null -> inMemory
        else -> stored
    }
}
