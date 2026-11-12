package com.dazix.caelis.ui.horizon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R
import com.dazix.caelis.data.horizon.HorizonState
import com.dazix.caelis.data.horizon.offerText
import com.dazix.caelis.ui.AppIcons

/**
 * A run of terrain downloads waiting for a tap, on an AR screen: its size and a Download button.
 *
 * Not a dialog. The sky works without the terrain, and a modal question over the viewfinder each
 * time a position needs tiles would be worse than the download it asks about. Closing it hides it
 * for the run; the same offer stays in Settings.
 */
@Composable
fun TerrainDownloadOfferChip(
    state: HorizonState.AwaitingConfirmation,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color(0xE61A1A1A),
    contentColor: Color = Color.White,
    accentColor: Color = Color(0xFF66BB6A),
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .widthIn(max = 480.dp)
            .background(containerColor, shape)
            .border(1.dp, contentColor.copy(alpha = 0.2f), shape)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = AppIcons.TerrainHorizon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = state.offerText(LocalContext.current),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            modifier = Modifier.weight(1f, fill = false),
        )
        TextButton(onClick = onDownload) {
            Text(
                text = stringResource(R.string.horizon_offer_download),
                color = accentColor,
                fontWeight = FontWeight.Bold,
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = AppIcons.Clear,
                contentDescription = stringResource(R.string.action_dismiss),
                tint = contentColor.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The same offer in Settings' terrain status line, in the theme's colours. */
@Composable
fun TerrainDownloadOfferRow(
    state: HorizonState.AwaitingConfirmation,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = state.offerText(LocalContext.current),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onDownload) {
            Text(stringResource(R.string.horizon_offer_download))
        }
    }
}
