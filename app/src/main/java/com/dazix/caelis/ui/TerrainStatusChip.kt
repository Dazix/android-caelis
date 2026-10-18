package com.dazix.caelis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
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
import com.dazix.caelis.data.horizon.HorizonState

/**
 * Says on the AR screen that the terrain skyline is still on its way: which tile is downloading and
 * how far, or that the profile is being computed. Shown in the same column as the compass chip,
 * because a percentage tucked into the top bar went unnoticed while the skyline was missing. Any
 * other state shows nothing; Settings reports the rest, failures included.
 *
 * The download's progress is per tile, so the text carries "x of y" as well as the percentage.
 * [nightVisionColor] replaces every colour while night vision is on.
 */
@Composable
fun TerrainStatusChip(state: HorizonState, modifier: Modifier = Modifier, nightVisionColor: Color? = null) {
    val progress: Float
    val text = when (state) {
        is HorizonState.Downloading -> {
            progress = state.progress
            stringResource(R.string.ar_terrain_downloading, state.index, state.total, percent(progress))
        }
        is HorizonState.Computing -> {
            progress = state.progress
            stringResource(R.string.ar_terrain_computing, percent(progress))
        }
        else -> return
    }
    Row(
        modifier = modifier
            .background(Color(0xDD1A1A1A), CircleShape)
            .border(1.dp, (nightVisionColor ?: Color.White).copy(alpha = 0.2f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.size(16.dp),
            color = nightVisionColor ?: Color.White,
            trackColor = (nightVisionColor ?: Color.White).copy(alpha = 0.25f),
            strokeWidth = 2.dp,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = nightVisionColor ?: Color.White,
        )
    }
}

private fun percent(progress: Float): Int = (progress.coerceIn(0f, 1f) * 100).toInt()
