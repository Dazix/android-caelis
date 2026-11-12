package com.dazix.caelis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R
import com.dazix.caelis.data.horizon.HorizonState
import com.dazix.caelis.data.horizon.sizeText

/**
 * Says on the AR screen that the terrain skyline is still on its way: which tile is downloading and
 * how far, or that the profile is being computed. Shown in the same column as the compass chip,
 * because a percentage tucked into the top bar went unnoticed while the skyline was missing.
 *
 * With [layerOn] it also says when the data is missing and the app may not fetch it — the download
 * was declined, or this build has no network — since the screen otherwise just has no skyline. That
 * chip is a button to [onOpenSettings]. Any other state shows nothing; Settings reports the rest,
 * failures included.
 *
 * The download's progress is of the whole run: "x of y" tiles and the bytes of all of them.
 * [nightVisionColor] replaces every colour while night vision is on.
 */
@Composable
fun TerrainStatusChip(
    state: HorizonState,
    layerOn: Boolean,
    modifier: Modifier = Modifier,
    nightVisionColor: Color? = null,
    onOpenSettings: () -> Unit = {},
) {
    val progress: Float?
    val text = when {
        state is HorizonState.Downloading -> {
            progress = state.progress
            // The ring shows how far; the text says how much, which is what costs on a metered plan.
            stringResource(R.string.ar_terrain_downloading, state.index, state.total, state.sizeText(LocalContext.current))
        }
        state is HorizonState.Sizing -> {
            progress = 0f
            stringResource(R.string.horizon_status_sizing)
        }
        state is HorizonState.Computing -> {
            progress = state.progress
            stringResource(R.string.ar_terrain_computing, percent(progress))
        }
        state is HorizonState.NeedsTiles && layerOn -> {
            progress = null
            stringResource(R.string.ar_terrain_missing)
        }
        else -> return
    }
    // Only the missing-data chip leads anywhere: Settings, scrolled to where the data is fetched.
    val tap = if (progress == null) {
        Modifier
            .clip(CircleShape)
            .clickable(
                onClickLabel = stringResource(R.string.ar_terrain_missing_action),
                role = Role.Button,
                onClick = onOpenSettings,
            )
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .then(tap)
            .background(Color(0xDD1A1A1A), CircleShape)
            .border(1.dp, (nightVisionColor ?: Color.White).copy(alpha = 0.2f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (progress != null) {
            CircularProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.size(16.dp),
                color = nightVisionColor ?: Color.White,
                trackColor = (nightVisionColor ?: Color.White).copy(alpha = 0.25f),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = AppIcons.TerrainHorizon,
                contentDescription = null,
                tint = nightVisionColor ?: MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = nightVisionColor ?: Color.White,
        )
    }
}

private fun percent(progress: Float): Int = (progress.coerceIn(0f, 1f) * 100).toInt()

/**
 * "↑ 30 m" while the height above terrain is not the default. The value lives for the process only,
 * and a height left from a lookout tower would otherwise skew the next horizon without a word; the
 * chip opens the screen's sheet, where it is changed.
 */
@Composable
fun HeightAboveTerrainChip(
    heightCm: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    nightVisionColor: Color? = null,
) {
    if (heightCm == com.dazix.caelis.data.HorizonSettings.DEFAULT_EYE_HEIGHT_CM) return
    val meters = formatHeightMeters(heightCm)
    val description = stringResource(R.string.horizon_height_chip_desc, meters)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .background(Color(0xDD1A1A1A), CircleShape)
            .border(1.dp, (nightVisionColor ?: Color.White).copy(alpha = 0.2f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = AppIcons.TerrainHorizon,
            contentDescription = null,
            tint = nightVisionColor ?: Color.White,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.horizon_height_chip, meters),
            style = MaterialTheme.typography.labelSmall,
            color = nightVisionColor ?: Color.White,
        )
    }
}
