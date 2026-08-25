package com.dazix.intervalometer.domain.nightsky

import java.time.LocalTime

/**
 * Scotopic color themes designed for dark-adapted eye protection and AMOLED true black contrast.
 */
enum class NightVisionTheme { RED, AMBER, GREEN }

/**
 * Operational mode governing when night vision activates.
 */
enum class NightVisionActivationMode { OFF, AUTO_SOLAR, CUSTOM_TIME }

/**
 * Combined immutable state representing the active Night Vision configuration.
 */
data class NightVisionState(
    val isActive: Boolean = true,
    val theme: NightVisionTheme = NightVisionTheme.AMBER,
)

/**
 * Pure evaluation helper determining if [current] falls within [start]..[end],
 * safely handling overnight spans across midnight (e.g. 22:00 to 06:00).
 */
fun isTimeInRange(current: LocalTime, start: LocalTime, end: LocalTime): Boolean {
    return if (start <= end) {
        current >= start && current < end
    } else {
        current >= start || current < end
    }
}
