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
import com.dazix.caelis.ar.pose.PoseIndicator

/**
 * Says which tracker is drawing the sky: the phone's own sensors, or ARCore.
 *
 * Shown only when the user has agreed to ARCore. Without that there is one possible source and a
 * permanent "sensors" label would be noise; with it, which one is active changes on its own (a dark
 * sky pauses ARCore within moments) and the person looking at a slightly different overlay deserves
 * to know why.
 *
 * The source is in the text and the icon, never in the colour alone. [nightVisionColor] replaces
 * every colour while night vision is on, so the chip cannot break dark adaptation.
 */
@Composable
fun PoseSourceChip(
    indicator: PoseIndicator,
    arCoreConsented: Boolean,
    modifier: Modifier = Modifier,
    nightVisionColor: Color? = null,
) {
    if (!arCoreConsented) return
    val text = stringResource(
        when (indicator) {
            PoseIndicator.SENSOR -> R.string.ar_pose_source_sensor
            PoseIndicator.ARCORE -> R.string.ar_pose_source_arcore
            PoseIndicator.SWITCHING -> R.string.ar_pose_source_switching
        },
    )
    val icon = when (indicator) {
        PoseIndicator.SENSOR -> AppIcons.Sensors
        PoseIndicator.ARCORE -> AppIcons.Explore
        PoseIndicator.SWITCHING -> AppIcons.Pending
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
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = nightVisionColor ?: MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = nightVisionColor ?: Color.White,
        )
    }
}
