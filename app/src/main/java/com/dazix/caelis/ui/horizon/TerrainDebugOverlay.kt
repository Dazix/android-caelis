package com.dazix.caelis.ui.horizon

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dazix.caelis.R

/**
 * Isolated diagnostic HUD overlay for the Terrain Horizon subsystem.
 *
 * Passes primitive values so live range/tile updates only recompose this isolated card
 * and never trigger recompositions of the heavy AR vector canvas or hardware AGSL shaders.
 */
@Composable
fun TerrainDebugOverlay(
    effectiveRangeKm: Int?,
    activeTileCount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color(0xCC1A1C1E),
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, Color(0x40FFFFFF)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "TERRAIN DIAGNOSTICS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                ),
                color = Color(0xFF80D8FF),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val rangeStr = effectiveRangeKm?.let { "${it} km" } ?: "—"
                Text(
                    text = stringResource(R.string.terrain_debug_auto_range, rangeStr),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    color = Color.White,
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0x80FFFFFF),
                )
                Text(
                    text = stringResource(R.string.terrain_debug_dem_tiles, activeTileCount),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    color = Color.White,
                )
            }
        }
    }
}
