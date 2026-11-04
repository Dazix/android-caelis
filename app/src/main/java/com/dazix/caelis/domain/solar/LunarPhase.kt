package com.dazix.caelis.domain.solar

/**
 * The eight named phases of the Moon. The name is the UI's to translate (`moon_phase_*` strings);
 * it used to be a Czech string here and showed in Czech in every language.
 */
enum class LunarPhase(val glyph: String) {
    NEW("🌑"),
    WAXING_CRESCENT("🌒"),
    FIRST_QUARTER("🌓"),
    WAXING_GIBBOUS("🌔"),
    FULL("🌕"),
    WANING_GIBBOUS("🌖"),
    LAST_QUARTER("🌗"),
    WANING_CRESCENT("🌘");

    companion object {
        /** Phase for the Moon-Sun elongation [elongationDegrees], each phase centred on its 45° step. */
        fun fromElongation(elongationDegrees: Double): LunarPhase {
            val norm = (elongationDegrees % 360.0 + 360.0) % 360.0
            return entries[((norm + 22.5) / 45.0).toInt() % 8]
        }
    }
}
