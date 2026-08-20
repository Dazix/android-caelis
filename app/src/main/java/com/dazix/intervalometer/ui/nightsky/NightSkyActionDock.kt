package com.dazix.intervalometer.ui.nightsky

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dazix.intervalometer.R
import com.dazix.intervalometer.ar.RedPrimary
import com.dazix.intervalometer.ar.RedSecondary
import com.dazix.intervalometer.ui.AppIcons

/**
 * Material 3 Expressive Action Dock for Night Sky AR.
 *
 * Provides quick 1-tap toggles for:
 * - Planetarium Opaque Sky Dome (blocks camera stream)
 * - Hardware-Accelerated AGSL Milky Way Layer
 * - Hardware-Accelerated AGSL Atmosphere Horizon Glow Layer
 * - Hardware-Accelerated AGSL Photorealistic Landscape Ground Layer
 * - 3D Celestial Dome Coordinate Grid
 * - Constellation Stick Figures
 * - Azimuth Calibration Reset
 */
@Composable
fun NightSkyActionDock(
    isSkyBackgroundOpaque: Boolean,
    isMilkyWayVisible: Boolean,
    isAtmosphereVisible: Boolean,
    isLandscapeVisible: Boolean,
    isGridVisible: Boolean,
    isConstellationsVisible: Boolean,
    redVisionMode: Boolean,
    isCalibrated: Boolean = false,
    onToggleSkyBackgroundOpaque: () -> Unit,
    onToggleMilkyWayVisible: () -> Unit,
    onToggleAtmosphereVisible: () -> Unit,
    onToggleLandscapeVisible: () -> Unit,
    onToggleGridVisible: () -> Unit,
    onToggleConstellationsVisible: () -> Unit,
    onResetCalibration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (redVisionMode) Color(0xF0180000) else Color(0xF0151820)
    val contentColor = if (redVisionMode) RedPrimary else Color(0xFFE0E0E0)
    val borderColor = if (redVisionMode) RedPrimary else Color(0xFF4DD0E1)
    val activeTint = if (redVisionMode) RedPrimary else Color(0xFF00ACC1)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. Reset Calibration Button (only if rotated/calibrated)
        if (isCalibrated) {
            DockActionButton(
                icon = AppIcons.ResetCalibration,
                contentDescription = stringResource(R.string.sun_ar_calib_reset),
                isActive = false,
                containerColor = containerColor,
                contentColor = contentColor,
                borderColor = borderColor,
                activeColor = activeTint,
                redVisionMode = redVisionMode,
                onClick = onResetCalibration,
            )
        }

        // 2. Planetarium Mode (Opaque Sky Background) Toggle
        DockActionButton(
            icon = AppIcons.Planetarium,
            contentDescription = stringResource(R.string.night_sky_opaque_bg_desc),
            isActive = isSkyBackgroundOpaque,
            containerColor = containerColor,
            contentColor = contentColor,
            borderColor = borderColor,
            activeColor = activeTint,
            redVisionMode = redVisionMode,
            onClick = onToggleSkyBackgroundOpaque,
        )

        // 3. Milky Way AGSL Layer Toggle
        DockActionButton(
            icon = AppIcons.MilkyWay,
            contentDescription = stringResource(R.string.night_sky_milky_way_desc),
            isActive = isMilkyWayVisible,
            containerColor = containerColor,
            contentColor = contentColor,
            borderColor = borderColor,
            activeColor = activeTint,
            redVisionMode = redVisionMode,
            onClick = onToggleMilkyWayVisible,
        )

        // 4. Atmosphere AGSL Layer & Star Extinction Toggle
        DockActionButton(
            icon = AppIcons.Atmosphere,
            contentDescription = stringResource(R.string.night_sky_atmosphere_desc),
            isActive = isAtmosphereVisible,
            containerColor = containerColor,
            contentColor = contentColor,
            borderColor = borderColor,
            activeColor = activeTint,
            redVisionMode = redVisionMode,
            onClick = onToggleAtmosphereVisible,
        )

        // 5. Landscape AGSL Ground Layer Toggle
        DockActionButton(
            icon = AppIcons.Landscape,
            contentDescription = stringResource(R.string.night_sky_landscape_desc),
            isActive = isLandscapeVisible,
            containerColor = containerColor,
            contentColor = contentColor,
            borderColor = borderColor,
            activeColor = activeTint,
            redVisionMode = redVisionMode,
            onClick = onToggleLandscapeVisible,
        )

        // 6. 3D Celestial Coordinate Grid Toggle
        DockActionButton(
            icon = AppIcons.Grid,
            contentDescription = stringResource(R.string.night_sky_grid_desc),
            isActive = isGridVisible,
            containerColor = containerColor,
            contentColor = contentColor,
            borderColor = borderColor,
            activeColor = activeTint,
            redVisionMode = redVisionMode,
            onClick = onToggleGridVisible,
        )

        // 7. Constellations Toggle
        DockActionButton(
            icon = AppIcons.Constellation,
            contentDescription = stringResource(R.string.night_sky_constellations_desc),
            isActive = isConstellationsVisible,
            containerColor = containerColor,
            contentColor = contentColor,
            borderColor = borderColor,
            activeColor = activeTint,
            redVisionMode = redVisionMode,
            onClick = onToggleConstellationsVisible,
        )
    }
}

@Composable
private fun DockActionButton(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color,
    activeColor: Color,
    redVisionMode: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (isActive) activeColor else containerColor
    val iconTint = if (isActive) {
        if (redVisionMode) Color.Black else Color.White
    } else {
        contentColor
    }

    Surface(
        shape = CircleShape,
        color = bgColor,
        border = BorderStroke(1.2.dp, borderColor),
        modifier = Modifier.size(46.dp),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
