package com.dazix.intervalometer.ui.nightsky

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dazix.intervalometer.R
import com.dazix.intervalometer.ar.paletteForTheme
import com.dazix.intervalometer.domain.nightsky.NightVisionTheme
import com.dazix.intervalometer.ui.AppIcons

/**
 * Modal bottom sheet content for configuring Night Sky AR layers and visual toggles.
 * Fully compliant with Night Vision astrophotography mode themes (Red, Amber, NVG Green).
 */
@Composable
fun NightSkyLayerSettingsSheet(
    isLandscapeVisible: Boolean,
    isAtmosphereVisible: Boolean,
    isMilkyWayVisible: Boolean,
    isConstellationsVisible: Boolean,
    isConstellationArtVisible: Boolean,
    isGridVisible: Boolean,
    isSkyBackgroundOpaque: Boolean,
    redVisionMode: Boolean,
    isSensorTrackingEnabled: Boolean = true,
    isKeepScreenOn: Boolean = false,
    isDebugCalibrationVisible: Boolean = false,
    isDebugFocusRegionVisible: Boolean = false,
    isDebugOptionsEnabled: Boolean = false,
    onToggleLandscapeVisible: () -> Unit,
    onToggleAtmosphereVisible: () -> Unit,
    onToggleMilkyWayVisible: () -> Unit,
    onToggleConstellationsVisible: () -> Unit,
    onToggleConstellationArtVisible: () -> Unit,
    onToggleGridVisible: () -> Unit,
    onToggleSkyBackgroundOpaque: () -> Unit,
    onToggleRedVisionMode: () -> Unit,
    onToggleSensorTracking: () -> Unit = {},
    onToggleKeepScreenOn: () -> Unit = {},
    onToggleDebugCalibration: () -> Unit = {},
    onToggleDebugFocusRegion: () -> Unit = {},
    nightVisionTheme: NightVisionTheme = NightVisionTheme.AMBER,
    modifier: Modifier = Modifier,
) {
    val palette = paletteForTheme(nightVisionTheme)
    val titleColor = if (redVisionMode) palette.primary else Color.White
    val headlineColor = if (redVisionMode) palette.primary else Color(0xFFE2E8F0)
    val iconColor = if (redVisionMode) palette.primary else Color(0xFF4DD0E1)

    val switchColors = if (redVisionMode) {
        SwitchDefaults.colors(
            checkedThumbColor = Color.Black,
            checkedTrackColor = palette.primary,
            checkedBorderColor = palette.primary,
            uncheckedThumbColor = palette.secondary,
            uncheckedTrackColor = Color(0xFF201010),
            uncheckedBorderColor = palette.secondary.copy(alpha = 0.3f),
        )
    } else {
        SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Color(0xFF00ACC1),
            checkedBorderColor = Color(0xFF00ACC1),
            uncheckedThumbColor = Color(0xFF94A3B8),
            uncheckedTrackColor = Color(0xFF1E293B),
            uncheckedBorderColor = Color(0xFF475569),
        )
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.night_sky_layers_title),
            color = titleColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        LayerToggleRow(
            label = stringResource(R.string.night_sky_landscape),
            icon = AppIcons.Landscape,
            isChecked = isLandscapeVisible,
            headlineColor = headlineColor,
            iconColor = iconColor,
            switchColors = switchColors,
            onCheckedChange = { onToggleLandscapeVisible() },
        )

        LayerToggleRow(
            label = stringResource(R.string.night_sky_atmosphere),
            icon = AppIcons.Atmosphere,
            isChecked = isAtmosphereVisible,
            headlineColor = headlineColor,
            iconColor = iconColor,
            switchColors = switchColors,
            onCheckedChange = { onToggleAtmosphereVisible() },
        )

        LayerToggleRow(
            label = stringResource(R.string.night_sky_milky_way),
            icon = AppIcons.MilkyWay,
            isChecked = isMilkyWayVisible,
            headlineColor = headlineColor,
            iconColor = iconColor,
            switchColors = switchColors,
            onCheckedChange = { onToggleMilkyWayVisible() },
        )

        LayerToggleRow(
            label = stringResource(R.string.night_sky_constellations),
            icon = AppIcons.Constellation,
            isChecked = isConstellationsVisible,
            headlineColor = headlineColor,
            iconColor = iconColor,
            switchColors = switchColors,
            onCheckedChange = { onToggleConstellationsVisible() },
        )

        LayerToggleRow(
            label = stringResource(R.string.night_sky_constellation_art),
            icon = AppIcons.Brush,
            isChecked = isConstellationArtVisible,
            headlineColor = headlineColor,
            iconColor = iconColor,
            switchColors = switchColors,
            onCheckedChange = { onToggleConstellationArtVisible() },
        )

        LayerToggleRow(
            label = stringResource(R.string.night_sky_grid),
            icon = AppIcons.Grid,
            isChecked = isGridVisible,
            headlineColor = headlineColor,
            iconColor = iconColor,
            switchColors = switchColors,
            onCheckedChange = { onToggleGridVisible() },
        )

        LayerToggleRow(
            label = stringResource(R.string.night_sky_opaque_bg),
            icon = AppIcons.Planetarium,
            isChecked = isSkyBackgroundOpaque,
            headlineColor = headlineColor,
            iconColor = iconColor,
            switchColors = switchColors,
            onCheckedChange = { onToggleSkyBackgroundOpaque() },
        )

        LayerToggleRow(
            label = stringResource(R.string.sun_ar_red_vision),
            icon = AppIcons.RedVision,
            isChecked = redVisionMode,
            headlineColor = headlineColor,
            iconColor = iconColor,
            switchColors = switchColors,
            onCheckedChange = { onToggleRedVisionMode() },
        )

        LayerToggleRow(
            label = stringResource(R.string.night_sky_sensor_tracking),
            icon = AppIcons.Sensors,
            isChecked = isSensorTrackingEnabled,
            headlineColor = headlineColor,
            iconColor = iconColor,
            switchColors = switchColors,
            onCheckedChange = { onToggleSensorTracking() },
        )

        LayerToggleRow(
            label = stringResource(R.string.night_sky_keep_screen_on),
            icon = AppIcons.ScreenAwake,
            isChecked = isKeepScreenOn,
            headlineColor = headlineColor,
            iconColor = iconColor,
            switchColors = switchColors,
            onCheckedChange = { onToggleKeepScreenOn() },
        )

        if (isDebugOptionsEnabled) {
            LayerToggleRow(
                label = stringResource(R.string.night_sky_debug_calibration),
                icon = AppIcons.Setup,
                isChecked = isDebugCalibrationVisible,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                onCheckedChange = { onToggleDebugCalibration() },
            )

            LayerToggleRow(
                label = stringResource(R.string.night_sky_debug_focus_region),
                icon = AppIcons.FocusArea,
                isChecked = isDebugFocusRegionVisible,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                onCheckedChange = { onToggleDebugFocusRegion() },
            )
        }
    }
}

@Composable
private fun LayerToggleRow(
    label: String,
    icon: ImageVector,
    isChecked: Boolean,
    headlineColor: Color,
    iconColor: Color,
    switchColors: androidx.compose.material3.SwitchColors,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp),
            )
        },
        trailingContent = {
            Switch(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                colors = switchColors,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!isChecked) }
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = label,
            color = headlineColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
