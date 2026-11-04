package com.dazix.caelis.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dazix.caelis.R
import com.dazix.caelis.domain.solar.CardinalDirection
import com.dazix.caelis.domain.solar.LunarPhase

/** The `sun_ar_cardinal_*` string of this compass point, e.g. "SE (135°)". */
@StringRes
fun CardinalDirection.labelRes(): Int = when (this) {
    CardinalDirection.N -> R.string.sun_ar_cardinal_n
    CardinalDirection.NE -> R.string.sun_ar_cardinal_ne
    CardinalDirection.E -> R.string.sun_ar_cardinal_e
    CardinalDirection.SE -> R.string.sun_ar_cardinal_se
    CardinalDirection.S -> R.string.sun_ar_cardinal_s
    CardinalDirection.SW -> R.string.sun_ar_cardinal_sw
    CardinalDirection.W -> R.string.sun_ar_cardinal_w
    CardinalDirection.NW -> R.string.sun_ar_cardinal_nw
}

/** The compass point's letters in the user's language, without the degrees, e.g. "JV". */
@Composable
fun cardinalName(direction: CardinalDirection): String =
    stringResource(direction.labelRes()).substringBefore(" ")

@StringRes
fun LunarPhase.nameRes(): Int = when (this) {
    LunarPhase.NEW -> R.string.moon_phase_new
    LunarPhase.WAXING_CRESCENT -> R.string.moon_phase_waxing_crescent
    LunarPhase.FIRST_QUARTER -> R.string.moon_phase_first_quarter
    LunarPhase.WAXING_GIBBOUS -> R.string.moon_phase_waxing_gibbous
    LunarPhase.FULL -> R.string.moon_phase_full
    LunarPhase.WANING_GIBBOUS -> R.string.moon_phase_waning_gibbous
    LunarPhase.LAST_QUARTER -> R.string.moon_phase_last_quarter
    LunarPhase.WANING_CRESCENT -> R.string.moon_phase_waning_crescent
}
