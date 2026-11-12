package com.dazix.caelis.ui.nightsky

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dazix.caelis.R
import com.dazix.caelis.ar.scene.paletteForTheme
import com.dazix.caelis.domain.nightsky.ConstellationArtOpacity
import com.dazix.caelis.domain.nightsky.NightVisionTheme
import com.dazix.caelis.domain.nightsky.TimeState
import com.dazix.caelis.ui.AppIcons
import com.dazix.caelis.ui.HeightAboveTerrainRow
import com.dazix.caelis.ui.LayerGroupCard
import com.dazix.caelis.ui.LayerSliderRow
import com.dazix.caelis.ui.LayerToggleRow
import com.dazix.caelis.ui.NudgeRow
import com.dazix.caelis.ui.formatOffsetDegrees
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Modal bottom sheet content for configuring Night Sky AR layers and visual toggles.
 * Fully compliant with Night Vision astrophotography mode themes (Red, Amber, NVG Green).
 * Switches are logically grouped into section cards for quick recognition and clean hierarchy.
 */
@Composable
fun NightSkyLayerSettingsSheet(
    isLandscapeVisible: Boolean,
    isRealHorizonEnabled: Boolean = false,
    isRealHorizonAvailable: Boolean = false,
    isPeakNamesVisible: Boolean = true,
    /** The height the sheet shows; the screen commits it when the sheet closes. Shared with Sun AR. */
    heightAboveTerrainCm: Int = com.dazix.caelis.data.HorizonSettings.DEFAULT_EYE_HEIGHT_CM,
    onHeightAboveTerrainChange: (Int) -> Unit = {},
    isAtmosphereVisible: Boolean,
    isMilkyWayVisible: Boolean,
    isPlanetsVisible: Boolean,
    isDsosVisible: Boolean = true,
    isMeteorShowersVisible: Boolean = false,
    isConstellationsVisible: Boolean,
    isConstellationArtVisible: Boolean,
    constellationArtOpacity: Float = ConstellationArtOpacity.DEFAULT,
    isAsterismNamesVisible: Boolean = true,
    isGridVisible: Boolean,
    isSkyBackgroundOpaque: Boolean,
    redVisionMode: Boolean,
    isSensorTrackingEnabled: Boolean = true,
    isKeepScreenOn: Boolean = false,
    isDebugFocusRegionVisible: Boolean = false,
    enableNightSkyFocusRegion: Boolean = false,
    isDebugOptionsEnabled: Boolean = false,
    timeState: TimeState = TimeState(),
    onSetVirtualTime: (Instant) -> Unit = {},
    onStepVirtualTime: (Duration) -> Unit = {},
    onResetToLive: () -> Unit = {},
    onToggleLandscapeVisible: () -> Unit,
    onToggleRealHorizon: () -> Unit = {},
    onTogglePeakNames: () -> Unit = {},
    onToggleAtmosphereVisible: () -> Unit,
    onToggleMilkyWayVisible: () -> Unit,
    onTogglePlanetsVisible: () -> Unit,
    onToggleDsosVisible: () -> Unit = {},
    onToggleMeteorShowersVisible: () -> Unit = {},
    onToggleConstellationsVisible: () -> Unit,
    onToggleConstellationArtVisible: () -> Unit,
    onConstellationArtOpacityChange: (Float) -> Unit = {},
    onConstellationArtOpacityChangeFinished: () -> Unit = {},
    onToggleAsterismNamesVisible: () -> Unit = {},
    onToggleGridVisible: () -> Unit,
    onToggleSkyBackgroundOpaque: () -> Unit,
    onToggleRedVisionMode: () -> Unit,
    onToggleSensorTracking: () -> Unit = {},
    onToggleKeepScreenOn: () -> Unit = {},
    onToggleDebugFocusRegion: () -> Unit = {},
    alignAzimuthOffsetDeg: Float = 0f,
    alignElevationOffsetDeg: Float = 0f,
    onNudgeAlignment: (Float, Float) -> Unit = { _, _ -> },
    onResetAlignment: () -> Unit = {},
    /** False under a virtual place: no real sky to align against, so every alignment control is disabled. */
    alignmentEnabled: Boolean = true,
    nightVisionTheme: NightVisionTheme = NightVisionTheme.AMBER,
    modifier: Modifier = Modifier,
    /** The zone the virtual clock is set and shown in: the observer's (a virtual place has its own). */
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val palette = paletteForTheme(nightVisionTheme)
    val titleColor = if (redVisionMode) palette.primary else Color.White
    val headlineColor = if (redVisionMode) palette.primary else Color(0xFFE2E8F0)
    val iconColor = if (redVisionMode) palette.primary else Color(0xFF4DD0E1)
    val groupTitleColor = if (redVisionMode) palette.primary else Color(0xFF4DD0E1)
    val cardContainerColor = if (redVisionMode) Color(0xFF140505) else Color(0x331E293B)
    val cardBorderColor = if (redVisionMode) palette.primary.copy(alpha = 0.25f) else Color(0x33475569)
    val dividerColor = if (redVisionMode) palette.primary.copy(alpha = 0.15f) else Color(0x22475569)

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

    val sliderColors = if (redVisionMode) {
        SliderDefaults.colors(
            thumbColor = palette.primary,
            activeTrackColor = palette.primary,
            inactiveTrackColor = Color(0xFF3B0000),
        )
    } else {
        SliderDefaults.colors(
            thumbColor = Color(0xFF4DD0E1),
            activeTrackColor = Color(0xFF00ACC1),
            inactiveTrackColor = Color(0xFF1E293B),
        )
    }

    val scrollState = rememberScrollState()
    var showDsoLegend by remember { mutableStateOf(false) }

    val signedAzimuth = if (alignAzimuthOffsetDeg > 180f) alignAzimuthOffsetDeg - 360f else alignAzimuthOffsetDeg
    val isAligned = kotlin.math.abs(signedAzimuth) > 0.001f || kotlin.math.abs(alignElevationOffsetDeg) > 0.001f

    Column(
        modifier = modifier.verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.night_sky_layers_title),
            color = titleColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )

        // 0. Time Travel & Virtual Clock (Standalone Section)
        LayerGroupCard(
            title = stringResource(R.string.night_sky_group_time),
            titleColor = groupTitleColor,
            containerColor = cardContainerColor,
            borderColor = cardBorderColor,
            trailingHeader = if (timeState.isVirtual) {
                {
                    TextButton(
                        onClick = onResetToLive,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        // An action, not a status: a bare "LIVE" here read as "the sky is live now"
                        // while the virtual time it appears for was running.
                        Text(
                            text = stringResource(R.string.night_sky_time_set_live),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (redVisionMode) palette.primary else Color(0xFF66BB6A),
                        )
                    }
                }
            } else {
                null
            },
        ) {
            TimeTravelSectionContent(
                timeState = timeState,
                headlineColor = headlineColor,
                accentColor = iconColor,
                labelColor = if (redVisionMode) palette.secondary else Color(0xFF94A3B8),
                sliderColors = sliderColors,
                onSetVirtualTime = onSetVirtualTime,
                onStepVirtualTime = onStepVirtualTime,
                onResetToLive = onResetToLive,
                redVisionMode = redVisionMode,
                palette = palette,
                zone = zone,
            )
        }

        // 1. Celestial Objects & Constellations
        LayerGroupCard(
            title = stringResource(R.string.night_sky_group_celestial),
            titleColor = groupTitleColor,
            containerColor = cardContainerColor,
            borderColor = cardBorderColor,
        ) {
            LayerToggleRow(
                label = stringResource(R.string.night_sky_planets),
                icon = AppIcons.Planet,
                isChecked = isPlanetsVisible,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                onCheckedChange = { onTogglePlanetsVisible() },
            )
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
            LayerToggleRow(
                label = stringResource(R.string.night_sky_dsos),
                icon = AppIcons.DeepSky,
                isChecked = isDsosVisible,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                onCheckedChange = { onToggleDsosVisible() },
                onInfoClick = { showDsoLegend = true },
            )
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
            LayerToggleRow(
                label = stringResource(R.string.night_sky_meteor_showers),
                icon = AppIcons.MeteorShower,
                isChecked = isMeteorShowersVisible,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                onCheckedChange = { onToggleMeteorShowersVisible() },
            )
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
            LayerToggleRow(
                label = stringResource(R.string.night_sky_constellations),
                icon = AppIcons.Constellation,
                isChecked = isConstellationsVisible,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                onCheckedChange = { onToggleConstellationsVisible() },
            )
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
            // Asterism names are printed only with the figures, so the switch works only while they are on.
            LayerToggleRow(
                label = stringResource(R.string.night_sky_asterism_names),
                icon = AppIcons.AutoAwesome,
                isChecked = isAsterismNamesVisible && isConstellationsVisible,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                enabled = isConstellationsVisible,
                onCheckedChange = { onToggleAsterismNamesVisible() },
            )
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
            LayerToggleRow(
                label = stringResource(R.string.night_sky_constellation_art),
                icon = AppIcons.Brush,
                isChecked = isConstellationArtVisible,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                onCheckedChange = { onToggleConstellationArtVisible() },
            )
            // A selected figure's artwork is drawn even with the switch off, so the slider stays usable.
            LayerSliderRow(
                label = stringResource(R.string.night_sky_constellation_art_opacity),
                valueText = stringResource(
                    R.string.night_sky_constellation_art_opacity_value,
                    (constellationArtOpacity * 100f).roundToInt(),
                ),
                value = constellationArtOpacity,
                valueRange = ConstellationArtOpacity.MIN..ConstellationArtOpacity.MAX,
                headlineColor = headlineColor,
                accentColor = iconColor,
                sliderColors = sliderColors,
                onValueChange = onConstellationArtOpacityChange,
                onValueChangeFinished = onConstellationArtOpacityChangeFinished,
            )
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
            LayerToggleRow(
                label = stringResource(R.string.night_sky_milky_way),
                icon = AppIcons.MilkyWay,
                isChecked = isMilkyWayVisible,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                onCheckedChange = { onToggleMilkyWayVisible() },
            )
        }

        // 2. Environment & Horizon
        LayerGroupCard(
            title = stringResource(R.string.night_sky_group_environment),
            titleColor = groupTitleColor,
            containerColor = cardContainerColor,
            borderColor = cardBorderColor,
        ) {
            LayerToggleRow(
                label = stringResource(R.string.night_sky_atmosphere),
                icon = AppIcons.Atmosphere,
                isChecked = isAtmosphereVisible,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                onCheckedChange = { onToggleAtmosphereVisible() },
            )
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
            LayerToggleRow(
                label = stringResource(R.string.night_sky_landscape),
                icon = AppIcons.Landscape,
                isChecked = isLandscapeVisible,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                onCheckedChange = { onToggleLandscapeVisible() },
            )
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
            LayerToggleRow(
                label = stringResource(R.string.horizon_night_sky_real),
                icon = AppIcons.TerrainHorizon,
                isChecked = isRealHorizonEnabled,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                onCheckedChange = { onToggleRealHorizon() },
            )
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
            // Names stand on the real skyline, so the switch works only while that is drawn.
            LayerToggleRow(
                label = stringResource(R.string.horizon_peak_names),
                icon = AppIcons.TerrainHorizon,
                isChecked = isPeakNamesVisible && isRealHorizonEnabled && isRealHorizonAvailable,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                enabled = isRealHorizonEnabled && isRealHorizonAvailable,
                onCheckedChange = { onTogglePeakNames() },
            )
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
            HeightAboveTerrainRow(
                heightCm = heightAboveTerrainCm,
                onChange = onHeightAboveTerrainChange,
                headlineColor = headlineColor,
                iconColor = iconColor,
            )
        }

        // 3. Display & Helpers
        LayerGroupCard(
            title = stringResource(R.string.night_sky_group_display),
            titleColor = groupTitleColor,
            containerColor = cardContainerColor,
            borderColor = cardBorderColor,
        ) {
            LayerToggleRow(
                label = stringResource(R.string.night_sky_grid),
                icon = AppIcons.Grid,
                isChecked = isGridVisible,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                onCheckedChange = { onToggleGridVisible() },
            )
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
            LayerToggleRow(
                label = stringResource(R.string.night_sky_opaque_bg),
                icon = AppIcons.Planetarium,
                isChecked = isSkyBackgroundOpaque,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                onCheckedChange = { onToggleSkyBackgroundOpaque() },
            )
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
            LayerToggleRow(
                label = stringResource(R.string.sun_ar_red_vision),
                icon = AppIcons.RedVision,
                isChecked = redVisionMode,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                onCheckedChange = { onToggleRedVisionMode() },
            )
        }

        // 4. Sensors & Device
        LayerGroupCard(
            title = stringResource(R.string.night_sky_group_tracking),
            titleColor = groupTitleColor,
            containerColor = cardContainerColor,
            borderColor = cardBorderColor,
        ) {
            LayerToggleRow(
                label = stringResource(R.string.night_sky_sensor_tracking),
                icon = AppIcons.Sensors,
                isChecked = isSensorTrackingEnabled,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                onCheckedChange = { onToggleSensorTracking() },
            )
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
            LayerToggleRow(
                label = stringResource(R.string.night_sky_keep_screen_on),
                icon = AppIcons.ScreenAwake,
                isChecked = isKeepScreenOn,
                headlineColor = headlineColor,
                iconColor = iconColor,
                switchColors = switchColors,
                onCheckedChange = { onToggleKeepScreenOn() },
            )
        }

        // 5. Sky Alignment (Compass / Horizon corrections)
        LayerGroupCard(
            title = stringResource(R.string.ar_align_title),
            titleColor = groupTitleColor,
            containerColor = cardContainerColor,
            borderColor = cardBorderColor,
            trailingHeader = if (isAligned) {
                {
                    TextButton(
                        onClick = onResetAlignment,
                        enabled = alignmentEnabled,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.sun_ar_calib_reset),
                            color = if (alignmentEnabled) iconColor else iconColor.copy(alpha = 0.38f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            } else null,
        ) {
            AlignmentSectionContent(
                azimuthOffsetDeg = alignAzimuthOffsetDeg,
                elevationOffsetDeg = alignElevationOffsetDeg,
                headlineColor = headlineColor,
                accentColor = iconColor,
                onNudge = onNudgeAlignment,
                enabled = alignmentEnabled,
            )
        }

        // 6. Debug Options (conditional)
        if (enableNightSkyFocusRegion || isDebugOptionsEnabled) {
            LayerGroupCard(
                title = stringResource(R.string.night_sky_group_debug),
                titleColor = groupTitleColor,
                containerColor = cardContainerColor,
                borderColor = cardBorderColor,
            ) {
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

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showDsoLegend) {
        DsoLegendDialog(
            onDismiss = { showDsoLegend = false },
            redVisionMode = redVisionMode,
            nightVisionTheme = nightVisionTheme,
        )
    }
}

/** Azimuth and elevation nudges with a live readout inside the alignment group card. */
@Composable
private fun AlignmentSectionContent(
    azimuthOffsetDeg: Float,
    elevationOffsetDeg: Float,
    headlineColor: Color,
    accentColor: Color,
    onNudge: (Float, Float) -> Unit,
    enabled: Boolean = true,
) {
    val signedAzimuth = if (azimuthOffsetDeg > 180f) azimuthOffsetDeg - 360f else azimuthOffsetDeg
    val coarse = NightSkyArViewModel.COARSE_NUDGE_DEGREES
    val fine = NightSkyArViewModel.FINE_NUDGE_DEGREES

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (!enabled) {
            // Disabled, not hidden: the stored correction stays visible, and the reason is said.
            Text(
                text = stringResource(R.string.ar_align_disabled_virtual),
                style = MaterialTheme.typography.bodySmall,
                color = headlineColor,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        NudgeRow(
            label = stringResource(R.string.ar_align_azimuth),
            valueText = formatOffsetDegrees(signedAzimuth),
            decreaseDescription = stringResource(R.string.ar_align_azimuth_left),
            increaseDescription = stringResource(R.string.ar_align_azimuth_right),
            accentColor = accentColor,
            headlineColor = headlineColor,
            enabled = enabled,
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
            enabled = enabled,
            onCoarseDecrease = { onNudge(0f, -coarse) },
            onFineDecrease = { onNudge(0f, -fine) },
            onFineIncrease = { onNudge(0f, fine) },
            onCoarseIncrease = { onNudge(0f, coarse) },
        )
    }
}

@Composable
private fun TimeTravelSectionContent(
    timeState: TimeState,
    headlineColor: Color,
    accentColor: Color,
    labelColor: Color,
    sliderColors: SliderColors,
    onSetVirtualTime: (Instant) -> Unit,
    onStepVirtualTime: (Duration) -> Unit,
    onResetToLive: () -> Unit,
    redVisionMode: Boolean,
    palette: com.dazix.caelis.ar.scene.NightVisionPalette,
    zone: ZoneId,
) {
    val activeInstant = timeState.activeInstant()
    val zonedDateTime = remember(activeInstant, zone) { activeInstant.atZone(zone) }

    // The locale's own order and punctuation: a fixed "d. MMM" read "czw., 5. lis" in Polish.
    val dateChipFormatter = remember {
        val locale = Locale.getDefault()
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMMyyyy"), locale)
    }
    val timeChipFormatter = remember {
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    }
    val dateChipFormatted = remember(zonedDateTime) {
        zonedDateTime.format(dateChipFormatter)
    }
    val timeChipFormatted = remember(zonedDateTime) {
        zonedDateTime.format(timeChipFormatter)
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    // Anchor time for slider (updated when transitioning from live to virtual or setting custom time)
    var anchorInstant by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(timeState.isVirtual) {
        if (!timeState.isVirtual) {
            anchorInstant = Instant.now()
        }
    }

    // Offset in minutes from anchor
    val currentOffsetMinutes = remember(timeState, anchorInstant) {
        if (timeState.isVirtual && timeState.virtualTime != null) {
            Duration.between(anchorInstant, timeState.virtualTime).toMinutes().toFloat()
        } else {
            0f
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Status Row (fixed height 24dp to avoid vertical layout shifts)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (timeState.isVirtual) {
                                if (redVisionMode) palette.primary else Color(0xFF4DD0E1)
                            } else {
                                if (redVisionMode) palette.secondary else Color(0xFF66BB6A)
                            },
                            CircleShape,
                        )
                )
                Text(
                    text = if (timeState.isVirtual) {
                        stringResource(R.string.night_sky_virtual_clock_active)
                    } else {
                        stringResource(R.string.night_sky_time_live_desc)
                    },
                    color = headlineColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }

            if (timeState.isVirtual) {
                val totalMinutes = currentOffsetMinutes.roundToInt()
                val hours = totalMinutes / 60
                val mins = kotlin.math.abs(totalMinutes % 60)
                val sign = if (totalMinutes >= 0) "+" else "-"
                val offsetString = if (hours != 0) {
                    "$sign${kotlin.math.abs(hours)}h ${mins}m"
                } else {
                    "$sign${mins}m"
                }
                Text(
                    text = stringResource(R.string.night_sky_time_offset_label, offsetString),
                    color = accentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }

        // Date & Time Picker Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DateTimeChip(
                icon = AppIcons.Calendar,
                label = dateChipFormatted,
                contentDescription = stringResource(R.string.night_sky_select_date),
                accentColor = accentColor,
                containerColor = if (redVisionMode) Color(0xFF1E0000) else Color(0xFF1E293B).copy(alpha = 0.5f),
                borderColor = accentColor.copy(alpha = 0.35f),
                onClick = { showDatePicker = true },
                modifier = Modifier.weight(1.2f),
            )
            DateTimeChip(
                icon = AppIcons.Schedule,
                label = timeChipFormatted,
                contentDescription = stringResource(R.string.night_sky_select_time),
                accentColor = accentColor,
                containerColor = if (redVisionMode) Color(0xFF1E0000) else Color(0xFF1E293B).copy(alpha = 0.5f),
                borderColor = accentColor.copy(alpha = 0.35f),
                onClick = { showTimePicker = true },
                modifier = Modifier.weight(0.8f),
            )
        }

        // Timeline Slider (±24 hours = ±1440 min)
        Slider(
            value = currentOffsetMinutes.coerceIn(-1440f, 1440f),
            onValueChange = { offsetMin ->
                val target = anchorInstant.plus(Duration.ofMinutes(offsetMin.roundToLong()))
                onSetVirtualTime(target)
            },
            valueRange = -1440f..1440f,
            colors = sliderColors,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
        )

        // Step Buttons Row: [-1h] [-15m] [LIVE] [+15m] [+1h]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimeStepButton(
                label = stringResource(R.string.night_sky_time_step_minus_1h),
                accentColor = accentColor,
                modifier = Modifier.weight(1f),
                onStep = { onStepVirtualTime(Duration.ofHours(-1)) },
            )
            TimeStepButton(
                label = stringResource(R.string.night_sky_time_step_minus_15m),
                accentColor = accentColor,
                modifier = Modifier.weight(1.1f),
                onStep = { onStepVirtualTime(Duration.ofMinutes(-15)) },
            )
            TimeLiveButton(
                label = stringResource(R.string.night_sky_time_live),
                isVirtual = timeState.isVirtual,
                activeColor = if (redVisionMode) palette.primary else Color(0xFF66BB6A),
                inactiveColor = labelColor.copy(alpha = 0.4f),
                modifier = Modifier.weight(1.1f),
                onClick = onResetToLive,
            )
            TimeStepButton(
                label = stringResource(R.string.night_sky_time_step_plus_15m),
                accentColor = accentColor,
                modifier = Modifier.weight(1.1f),
                onStep = { onStepVirtualTime(Duration.ofMinutes(15)) },
            )
            TimeStepButton(
                label = stringResource(R.string.night_sky_time_step_plus_1h),
                accentColor = accentColor,
                modifier = Modifier.weight(1f),
                onStep = { onStepVirtualTime(Duration.ofHours(1)) },
            )
        }
    }

    if (showDatePicker) {
        NightSkyDatePickerDialog(
            initialDate = zonedDateTime.toLocalDate(),
            redVisionMode = redVisionMode,
            palette = palette,
            onDateSelected = { newDate ->
                val newZonedDateTime = newDate.atTime(zonedDateTime.toLocalTime()).atZone(zone)
                val newInstant = newZonedDateTime.toInstant()
                anchorInstant = newInstant
                onSetVirtualTime(newInstant)
            },
            onDismiss = { showDatePicker = false },
        )
    }

    if (showTimePicker) {
        NightSkyTimePickerDialog(
            initialTime = zonedDateTime.toLocalTime(),
            redVisionMode = redVisionMode,
            palette = palette,
            onTimeSelected = { newTime ->
                val newZonedDateTime = zonedDateTime.toLocalDate().atTime(newTime).atZone(zone)
                val newInstant = newZonedDateTime.toInstant()
                anchorInstant = newInstant
                onSetVirtualTime(newInstant)
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

@Composable
private fun DateTimeChip(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    accentColor: Color,
    containerColor: Color,
    borderColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = modifier
            .height(40.dp)
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = accentColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NightSkyDatePickerDialog(
    initialDate: LocalDate,
    redVisionMode: Boolean,
    palette: com.dazix.caelis.ar.scene.NightVisionPalette,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialMillis = remember(initialDate) {
        initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

    val datePickerColors = if (redVisionMode) {
        DatePickerDefaults.colors(
            containerColor = Color(0xFF140000),
            titleContentColor = palette.primary,
            headlineContentColor = palette.primary,
            weekdayContentColor = palette.secondary,
            subheadContentColor = palette.secondary,
            yearContentColor = palette.primary,
            currentYearContentColor = palette.primary,
            selectedYearContentColor = palette.primary,
            selectedDayContainerColor = palette.primary,
            selectedDayContentColor = Color.Black,
            dayContentColor = Color(0xFFFF8A80),
            todayContentColor = palette.primary,
            todayDateBorderColor = palette.primary,
        )
    } else {
        DatePickerDefaults.colors()
    }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { epochMs ->
                        val localDate = Instant.ofEpochMilli(epochMs)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                        onDateSelected(localDate)
                    }
                    onDismiss()
                },
            ) {
                Text(
                    text = stringResource(R.string.settings_confirm),
                    color = if (redVisionMode) palette.primary else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.settings_cancel),
                    color = if (redVisionMode) palette.secondary else MaterialTheme.colorScheme.secondary,
                )
            }
        },
        colors = DatePickerDefaults.colors(
            containerColor = if (redVisionMode) Color(0xFF140000) else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        DatePicker(
            state = datePickerState,
            colors = datePickerColors,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NightSkyTimePickerDialog(
    initialTime: LocalTime,
    redVisionMode: Boolean,
    palette: com.dazix.caelis.ar.scene.NightVisionPalette,
    onTimeSelected: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val is24Hour = DateFormat.is24HourFormat(context)
    val state = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = is24Hour,
    )

    val timePickerColors = if (redVisionMode) {
        TimePickerDefaults.colors(
            clockDialColor = Color(0xFF1E0000),
            clockDialSelectedContentColor = Color.Black,
            clockDialUnselectedContentColor = Color(0xFFFF8A80),
            selectorColor = palette.primary,
            timeSelectorSelectedContainerColor = Color(0xFF3B0000),
            timeSelectorSelectedContentColor = palette.primary,
            timeSelectorUnselectedContainerColor = Color(0xFF1E0000),
            timeSelectorUnselectedContentColor = Color(0xFFFF8A80),
            periodSelectorBorderColor = palette.primary.copy(alpha = 0.5f),
            periodSelectorSelectedContainerColor = Color(0xFF3B0000),
            periodSelectorUnselectedContainerColor = Color(0xFF1E0000),
            periodSelectorSelectedContentColor = palette.primary,
            periodSelectorUnselectedContentColor = Color(0xFFFF8A80),
        )
    } else {
        TimePickerDefaults.colors()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.night_sky_time_picker_title),
                style = MaterialTheme.typography.headlineSmall,
                color = if (redVisionMode) palette.primary else MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                TimePicker(
                    state = state,
                    colors = timePickerColors,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onTimeSelected(LocalTime.of(state.hour, state.minute))
                    onDismiss()
                },
            ) {
                Text(
                    text = stringResource(R.string.settings_confirm),
                    color = if (redVisionMode) palette.primary else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.settings_cancel),
                    color = if (redVisionMode) palette.secondary else MaterialTheme.colorScheme.secondary,
                )
            }
        },
        containerColor = if (redVisionMode) Color(0xFF140000) else MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun TimeStepButton(
    label: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onStep: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        modifier = modifier
            .height(32.dp)
            .clickable(onClick = onStep),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = label,
                color = accentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TimeLiveButton(
    label: String,
    isVirtual: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val borderColor = if (isVirtual) activeColor.copy(alpha = 0.5f) else inactiveColor.copy(alpha = 0.2f)
    val textColor = if (isVirtual) activeColor else inactiveColor
    val backgroundColor = if (isVirtual) activeColor.copy(alpha = 0.12f) else Color.Transparent

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = modifier
            .height(32.dp)
            .clickable(enabled = isVirtual, onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = label,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}


