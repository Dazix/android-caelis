package com.dazix.caelis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R
import com.dazix.caelis.domain.arframe.HeadingState

/**
 * Says what the AR heading rests on, and only when that matters: a compass reporting itself
 * unreliable, or a heading the user has locked by aligning. A good compass shows nothing.
 *
 * The state is in the text, never in the colour alone. [nightVisionColor] replaces every colour
 * while night vision is on, so the chip cannot break dark adaptation.
 */
@Composable
fun HeadingStatusChip(state: HeadingState, modifier: Modifier = Modifier, nightVisionColor: Color? = null) {
    val textRes = when (state) {
        HeadingState.UNRELIABLE, HeadingState.LOW -> R.string.ar_heading_compass_unreliable
        HeadingState.LOCKED -> R.string.ar_heading_locked
        HeadingState.OK -> return
    }
    val warning = state != HeadingState.LOCKED
    val text = stringResource(textRes)
    Row(
        modifier = modifier
            .background(Color(0xDD1A1A1A), CircleShape)
            .border(1.dp, (nightVisionColor ?: Color.White).copy(alpha = 0.2f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = AppIcons.Compass,
            contentDescription = null,
            tint = nightVisionColor ?: if (warning) MaterialTheme.colorScheme.error else Color(0xFF81C784),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = nightVisionColor ?: Color.White,
        )
    }
}
