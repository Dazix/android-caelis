package com.dazix.caelis.ui.horizon

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dazix.caelis.ui.AppIcons
import java.util.Locale

/**
 * Material Design 3 Expressive Horizon Lock Indicator.
 *
 * Utilizes fluid spring animations for scale bounce (`Spring.DampingRatioMediumBouncy`)
 * and tonal color transitions when alignment is acquired.
 */
@Composable
fun HorizonLockIndicator(
    isLocked: Boolean,
    residualDegrees: Float,
    modifier: Modifier = Modifier,
) {
    // M3 Expressive Spring Motion for scale bounce
    val scale by animateFloatAsState(
        targetValue = if (isLocked) 1.06f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "LockScaleBounce",
    )

    // Expressive Tonal Color Transitions
    val backgroundColor by animateColorAsState(
        targetValue = if (isLocked) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.88f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        },
        label = "LockBgColor",
    )

    val contentColor by animateColorAsState(
        targetValue = if (isLocked) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "LockContentColor",
    )

    val borderColor by animateColorAsState(
        targetValue = if (isLocked) {
            MaterialTheme.colorScheme.tertiary
        } else {
            Color.Transparent
        },
        label = "LockBorderColor",
    )

    Surface(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, borderColor, RoundedCornerShape(20.dp)),
        color = backgroundColor,
        shadowElevation = if (isLocked) 4.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (isLocked) AppIcons.TerrainLock else AppIcons.TerrainHorizon,
                contentDescription = if (isLocked) "Horizon Locked" else "Aligning Horizon",
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )

            Text(
                text = if (isLocked) {
                    val formatted = String.format(Locale.US, "±%.2f°", residualDegrees)
                    "LOCKED ($formatted)"
                } else {
                    "ALIGNING HORIZON…"
                },
                color = contentColor,
                fontSize = 12.sp,
                fontWeight = if (isLocked) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 0.4.sp,
            )
        }
    }
}
