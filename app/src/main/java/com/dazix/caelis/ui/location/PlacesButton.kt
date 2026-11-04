package com.dazix.caelis.ui.location

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R
import com.dazix.caelis.domain.location.SavedPlace
import com.dazix.caelis.ui.AppIcons
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The location chip's text under a virtual place: its name, and the zone its times are shown in
 * when that is not the phone's own (otherwise nothing would say that 06:12 is not the phone's 06:12).
 */
fun placeChipLabel(place: SavedPlace, deviceZone: ZoneId = ZoneId.systemDefault()): String {
    val zone = place.zone()
    if (zone.normalized() == deviceZone.normalized() || zone.id == deviceZone.id) return place.name
    return place.name + " · " + zone.getDisplayName(TextStyle.SHORT, Locale.getDefault())
}

/**
 * The map button that opens the places sheet. While a virtual place is shown it is filled with the
 * accent and carries a pin badge — a shape as well as a colour, so the state is not told by colour
 * alone — and its description names the place.
 *
 * @param containerColor the idle background, or [Color.Transparent] inside a bar that has its own.
 */
@Composable
fun PlacesButton(
    place: SavedPlace?,
    onClick: () -> Unit,
    accent: Color,
    idleTint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    containerColor: Color = Color.Transparent,
    borderColor: Color = Color.Transparent,
) {
    val active = place != null
    val description = if (place != null) {
        stringResource(R.string.places_button_virtual, place.name)
    } else {
        stringResource(R.string.places_button_gps)
    }
    Box(modifier = modifier.size(size)) {
        // The circle is drawn by this box, not by the IconButton: the button enforces a 48dp touch
        // target, and with the background on it the circle grew past the requested size (it stood
        // larger than the 44dp compass button next to it on the device).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(if (active) accent else containerColor, CircleShape)
                .border(1.dp, if (active) accent else borderColor, CircleShape),
        ) {
            IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = AppIcons.Map,
                    contentDescription = description,
                    tint = if (active) Color.Black else idleTint,
                    modifier = Modifier.size(size * 0.55f),
                )
            }
        }
        if (active) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(18.dp)
                    .background(Color.Black, CircleShape)
                    .border(1.dp, accent, CircleShape)
                    .padding(2.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Place,
                    contentDescription = null,
                    tint = accent,
                )
            }
        }
    }
}
