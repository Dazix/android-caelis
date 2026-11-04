package com.dazix.caelis.domain.solar

/**
 * The eight compass points. The letters are the UI's to translate (`sun_ar_cardinal_*` strings):
 * rise and set azimuths once printed the English "SE" beside a top bar that said "JV".
 */
enum class CardinalDirection {
    N, NE, E, SE, S, SW, W, NW;

    companion object {
        /** Compass point for [azimuthDegrees] from north, each point centred on its 45° sector. */
        fun of(azimuthDegrees: Double): CardinalDirection {
            val norm = (azimuthDegrees % 360.0 + 360.0) % 360.0
            return entries[((norm + 22.5) / 45.0).toInt() % 8]
        }
    }
}
