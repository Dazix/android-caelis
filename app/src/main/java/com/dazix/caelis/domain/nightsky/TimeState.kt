package com.dazix.caelis.domain.nightsky

import java.time.Instant

/**
 * Immutable state representing the active time context for the celestial sphere.
 * Pure Kotlin, no Android imports.
 *
 * @param isVirtual True if the user has shifted time away from the live device clock.
 * @param virtualTime The target [Instant] when [isVirtual] is true; null when live.
 */
data class TimeState(
    val isVirtual: Boolean = false,
    val virtualTime: Instant? = null,
) {
    /**
     * Resolves the active [Instant] for astronomical calculations.
     */
    fun activeInstant(): Instant = if (isVirtual && virtualTime != null) virtualTime else Instant.now()
}
