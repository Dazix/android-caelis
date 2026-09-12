package com.dazix.caelis.ui.nightsky

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dazix.caelis.R
import com.dazix.caelis.ar.paletteForTheme
import com.dazix.caelis.domain.nightsky.NightVisionTheme
import com.dazix.caelis.ui.AppIcons
import com.dazix.caelis.ui.formatOffsetDegrees

/**
 * Modal bottom sheet content for configuring Night Sky AR layers and visual toggles.
 * Fully compliant with Night Vision astrophotography mode themes (Red, Amber, NVG Green).
 */
@Composable
fun NightSkyLayerSettingsSheet(
    isLandscapeVisible: Boolean,
    isRealHorizonEnabled: Boolean = false,
    isRealHorizonAvailable: Boolean = false,
    isAtmosphereVisible: Boolean,
    isMilkyWayVisible: Boolean,
    isPlanetsVisible: Boolean,
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
    onToggleRealHorizon: () -> Unit = {},
    onToggleAtmosphereVisible: () -> Unit,
    onToggleMilkyWayVisible: () -> Unit,
    onTogglePlanetsVisible: () -> Unit,
    onToggleConstellationsVisible: () -> Unit,
    onToggleConstellationArtVisible: () -> Unit,
    onToggleGridVisible: () -> Unit,
    onToggleSkyBackgroundOpaque: () -> Unit,
    onToggleRedVisionMode: () -> Unit,
    onToggleSensorTracking: () -> Unit = {},
    onToggleKeepScreenOn: () -> Unit = {},
    onToggleDebugCalibration: () -> Unit = {},
    onToggleDebugFocusRegion: () -> Unit = {},
    alignAzimuthOffsetDeg: Float = 0f,
    alignElevationOffsetDeg: Float = 0f,
    onNudgeAlignment: (Float, Float) -> Unit = { _, _ -> },
    onResetAlignment: () -> Unit = {},
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

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier.verticalScroll(scrollState),
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

        // Offered only once the terrain layer is switched on in the app's Settings, which is also
        // where the radius, the eye height and the download live — they are shared with Sun AR.
        if (isRealHorizonAvailable) {
            LayerToggleRow(
                label = stringResource(R.string.horizon_night_sky_real),
                icon = AppIcons.Landscape,
                isChecked = isRealHorizonEnabled,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                onCheckedChange = { onToggleRealHorizon() },
            )
        }

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
            label = stringResource(R.string.night_sky_planets),
            icon = AppIcons.Planet,
            isChecked = isPlanetsVisible,
            headlineColor = headlineColor,
            iconColor = iconColor,
            switchColors = switchColors,
            onCheckedChange = { onTogglePlanetsVisible() },
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

        // The same correction the Sun AR screen offers, for the same reason: a phone compass is a
        // few degrees out and the sky has to be dragged onto the real skyline once per place.
        AlignmentSection(
            azimuthOffsetDeg = alignAzimuthOffsetDeg,
            elevationOffsetDeg = alignElevationOffsetDeg,
            headlineColor = headlineColor,
            accentColor = iconColor,
            onNudge = onNudgeAlignment,
            onReset = onResetAlignment,
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/** Azimuth and elevation nudges with a live readout. Mirrors the Sun AR alignment control. */
@Composable
private fun AlignmentSection(
    azimuthOffsetDeg: Float,
    elevationOffsetDeg: Float,
    headlineColor: Color,
    accentColor: Color,
    onNudge: (Float, Float) -> Unit,
    onReset: () -> Unit,
) {
    val signedAzimuth = if (azimuthOffsetDeg > 180f) azimuthOffsetDeg - 360f else azimuthOffsetDeg
    val coarse = NightSkyArViewModel.COARSE_NUDGE_DEGREES
    val fine = NightSkyArViewModel.FINE_NUDGE_DEGREES
    val isAligned = kotlin.math.abs(signedAzimuth) > 0.001f || kotlin.math.abs(elevationOffsetDeg) > 0.001f

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ar_align_title),
                style = MaterialTheme.typography.bodyMedium,
                color = headlineColor,
                modifier = Modifier.weight(1f),
            )
            if (isAligned) {
                TextButton(onClick = onReset) {
                    Text(
                        text = stringResource(R.string.sun_ar_calib_reset),
                        color = accentColor,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        NudgeRow(
            label = stringResource(R.string.ar_align_azimuth),
            valueText = formatOffsetDegrees(signedAzimuth),
            decreaseDescription = stringResource(R.string.ar_align_azimuth_left),
            increaseDescription = stringResource(R.string.ar_align_azimuth_right),
            accentColor = accentColor,
            headlineColor = headlineColor,
            onCoarseDecrease = { onNudge(-coarse, 0f) },
            onFineDecrease = { onNudge(-fine, 0f) },
            onFineIncrease = { onNudge(fine, 0f) },
            onCoarseIncrease = { onNudge(coarse, 0f) },
        )

        NudgeRow(
            label = stringResource(R.string.ar_align_elevation),
            valueText = formatOffsetDegrees(elevationOffsetDeg),
            decreaseDescription = stringResource(R.string.ar_align_elevation_down),
            increaseDescription = stringResource(R.string.ar_align_elevation_up),
            accentColor = accentColor,
            headlineColor = headlineColor,
            onCoarseDecrease = { onNudge(0f, -coarse) },
            onFineDecrease = { onNudge(0f, -fine) },
            onFineIncrease = { onNudge(0f, fine) },
            onCoarseIncrease = { onNudge(0f, coarse) },
        )
    }
}

@Composable
private fun NudgeRow(
    label: String,
    valueText: String,
    decreaseDescription: String,
    increaseDescription: String,
    accentColor: Color,
    headlineColor: Color,
    onCoarseDecrease: () -> Unit,
    onFineDecrease: () -> Unit,
    onFineIncrease: () -> Unit,
    onCoarseIncrease: () -> Unit,
) {
    @Composable
    fun NudgeButton(glyph: String, description: String, onClick: () -> Unit) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp).semantics { contentDescription = description },
        ) {
            Text(text = glyph, style = MaterialTheme.typography.labelLarge, color = accentColor)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = headlineColor,
            modifier = Modifier.weight(1f),
        )
        NudgeButton("⏪", decreaseDescription, onCoarseDecrease)
        NudgeButton("◀", decreaseDescription, onFineDecrease)
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelMedium,
            color = accentColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(56.dp),
        )
        NudgeButton("▶", increaseDescription, onFineIncrease)
        NudgeButton("⏩", increaseDescription, onCoarseIncrease)
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
