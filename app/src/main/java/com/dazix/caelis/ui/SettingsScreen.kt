package com.dazix.caelis.ui

import android.Manifest
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.dazix.caelis.BuildConfig
import com.dazix.caelis.ui.solar.HorizonConsentDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R
import com.dazix.caelis.domain.nightsky.NightVisionActivationMode
import com.dazix.caelis.data.DebugPreferences
import com.dazix.caelis.data.HorizonSettings
import com.dazix.caelis.data.horizon.DemTileDownloader
import com.dazix.caelis.data.horizon.HorizonState
import com.dazix.caelis.data.horizon.StorageMetrics
import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.nightsky.NightVisionTheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    theme: NightVisionTheme,
    activationMode: NightVisionActivationMode,
    startTime: LocalTime,
    endTime: LocalTime,
    debugPreferences: DebugPreferences = DebugPreferences(),
    storageMetrics: StorageMetrics = StorageMetrics(),
    horizonSettings: HorizonSettings = HorizonSettings(),
    horizonState: HorizonState = HorizonState.Idle,
    canDownloadTerrain: Boolean = true,
    missingTerrainTiles: List<DemTileKey> = emptyList(),
    cachedTerrainBytes: Long = 0L,
    /** The radius the automatic setting settled on, once it has been worked out. */
    effectiveRangeKm: Int? = null,
    onHorizonEnabledChange: (Boolean) -> Unit = {},
    onHorizonAutoRangeChange: (Boolean) -> Unit = {},
    onHorizonRangeChange: (Int) -> Unit = {},
    onHorizonEyeHeightChange: (Int) -> Unit = {},
    onHorizonShadeOccludedChange: (Boolean) -> Unit = {},
    onHorizonWifiOnlyChange: (Boolean) -> Unit = {},
    onHorizonRealInNightSkyChange: (Boolean) -> Unit = {},
    onHorizonNetworkConsent: (Boolean, Boolean?) -> Unit = { _, _ -> },
    onDownloadTerrain: () -> Unit = {},
    onImportTerrain: () -> Unit = {},
    onClearTerrainCache: () -> Unit = {},
    onClearDemCache: () -> Unit = onClearTerrainCache,
    onStartLocationUpdates: () -> Unit = {},
    onStopLocationUpdates: () -> Unit = {},
    onRefreshLocation: () -> Unit = {},
    onThemeChange: (NightVisionTheme) -> Unit,
    onActivationModeChange: (NightVisionActivationMode) -> Unit,
    onStartTimeChange: (LocalTime) -> Unit,
    onEndTimeChange: (LocalTime) -> Unit,
    onDebugPreferencesChange: (DebugPreferences) -> Unit = {},
    onNavigateToOfflineTerrain: () -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showStartTimeDialog by remember { mutableStateOf(false) }
    var showEndTimeDialog by remember { mutableStateOf(false) }
    var showAttribution by remember { mutableStateOf(false) }
    var showConsentDialog by remember { mutableStateOf(false) }
    var showClearCacheConfirmation by remember { mutableStateOf(false) }
    var pendingActionAfterConsent by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingLocationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var manualImportExpanded by remember { mutableStateOf(false) }
    var debugAccordionExpanded by remember { mutableStateOf(false) }
    val debugChevronRotation by animateFloatAsState(
        targetValue = if (debugAccordionExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "debug_chevron_rotation",
    )

    DisposableEffect(Unit) {
        onStartLocationUpdates()
        onDispose {
            onStopLocationUpdates()
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            onRefreshLocation()
            pendingLocationAction?.invoke()
            pendingLocationAction = null
        }
    }

    val requestLocationIfMissing = { onGranted: () -> Unit ->
        val hasLocation = Permissions.granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            Permissions.granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasLocation) {
            pendingLocationAction = onGranted
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        } else {
            onGranted()
        }
    }

    val handleHorizonToggle: (Boolean) -> Unit = { checked ->
        if (checked) {
            if (!horizonSettings.networkConsentAnswered && canDownloadTerrain) {
                pendingActionAfterConsent = {
                    requestLocationIfMissing {
                        onHorizonEnabledChange(true)
                    }
                }
                showConsentDialog = true
            } else {
                requestLocationIfMissing {
                    onHorizonEnabledChange(true)
                }
            }
        } else {
            onHorizonEnabledChange(false)
        }
    }

    val handleDownloadClick: () -> Unit = {
        if (!horizonSettings.networkConsentAnswered && canDownloadTerrain) {
            pendingActionAfterConsent = {
                requestLocationIfMissing {
                    onDownloadTerrain()
                }
            }
            showConsentDialog = true
        } else {
            requestLocationIfMissing {
                onDownloadTerrain()
            }
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.settings_subtitle),
        onBack = onBack,
    ) {
        // ---- Section 1: Display / Night Vision ----
        SectionHeader(text = stringResource(R.string.settings_section_display))

        SectionCard(
            title = stringResource(R.string.settings_theme_title),
            subtitle = stringResource(R.string.settings_theme_subtitle),
        ) {
            ThemeChoiceRow(
                currentTheme = theme,
                onThemeChange = onThemeChange,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        // ---- Section 2: Night Vision Activation Mode ----
        SectionCard(
            title = stringResource(R.string.settings_activation_title),
            subtitle = stringResource(R.string.settings_activation_subtitle),
        ) {
            ChoiceRow(
                title = stringResource(R.string.settings_activation_off),
                description = stringResource(R.string.settings_activation_off_desc),
                selected = activationMode == NightVisionActivationMode.OFF,
                onSelect = { onActivationModeChange(NightVisionActivationMode.OFF) },
            )
            ChoiceRow(
                title = stringResource(R.string.settings_activation_auto_solar),
                description = stringResource(R.string.settings_activation_auto_solar_desc),
                selected = activationMode == NightVisionActivationMode.AUTO_SOLAR,
                onSelect = { onActivationModeChange(NightVisionActivationMode.AUTO_SOLAR) },
            )
            ChoiceRow(
                title = stringResource(R.string.settings_activation_custom_time),
                description = stringResource(R.string.settings_activation_custom_time_desc),
                selected = activationMode == NightVisionActivationMode.CUSTOM_TIME,
                onSelect = { onActivationModeChange(NightVisionActivationMode.CUSTOM_TIME) },
            )
        }

        // ---- Section 3: Custom Time Schedule (Conditional) ----
        AnimatedVisibility(
            visible = activationMode == NightVisionActivationMode.CUSTOM_TIME,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            SectionCard(
                title = stringResource(R.string.settings_custom_schedule_title),
                subtitle = stringResource(
                    R.string.settings_custom_schedule_desc,
                    startTime.format(timeFormatter),
                    endTime.format(timeFormatter),
                ),
            ) {
                TimePickerRow(
                    label = stringResource(R.string.settings_time_start),
                    time = startTime,
                    onClick = { showStartTimeDialog = true },
                )
                TimePickerRow(
                    label = stringResource(R.string.settings_time_end),
                    time = endTime,
                    onClick = { showEndTimeDialog = true },
                )
            }
        }

        // ---- Section 3: Terrain horizon ----
        //
        // Shared by both AR screens on purpose: the search radius, the eye height and the downloaded
        // tiles describe where you are standing, not which screen you happen to be looking at. The
        // AR screens keep only their own show/hide switch.
        SectionHeader(
            text = stringResource(R.string.horizon_section_title),
            modifier = Modifier.padding(top = 8.dp),
        )

        SectionCard {
            SwitchRow(
                title = stringResource(R.string.horizon_enabled),
                description = stringResource(R.string.horizon_enabled_desc),
                checked = horizonSettings.enabled,
                onCheckedChange = handleHorizonToggle,
            )

            HorizonStatusRow(state = horizonState)

            if (!canDownloadTerrain) {
                Text(
                    text = stringResource(R.string.horizon_no_permission),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            Text(
                text = stringResource(R.string.horizon_range),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            // Automatic first and selected by default: the useful radius varies from two kilometres
            // in a valley to eighty on a plain, and guessing high costs tiles, not accuracy.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = horizonSettings.autoRange,
                    onClick = { onHorizonAutoRangeChange(true) },
                    label = {
                        Text(
                            if (horizonSettings.autoRange && effectiveRangeKm != null) {
                                stringResource(R.string.horizon_range_auto_value, effectiveRangeKm)
                            } else {
                                stringResource(R.string.horizon_range_auto)
                            },
                        )
                    },
                )
                HorizonSettings.RANGE_CHOICES_KM.forEach { km ->
                    FilterChip(
                        selected = !horizonSettings.autoRange && horizonSettings.maxRangeKm == km,
                        onClick = {
                            onHorizonAutoRangeChange(false)
                            onHorizonRangeChange(km)
                        },
                        label = { Text(stringResource(R.string.horizon_range_value, km)) },
                    )
                }
            }

            StepperRow(
                title = stringResource(R.string.horizon_eye_height),
                value = stringResource(R.string.horizon_eye_height_value, horizonSettings.eyeHeightCm),
                canDecrease = horizonSettings.eyeHeightCm > HorizonSettings.MIN_EYE_HEIGHT_CM,
                canIncrease = horizonSettings.eyeHeightCm < HorizonSettings.MAX_EYE_HEIGHT_CM,
                onDecrease = { onHorizonEyeHeightChange(horizonSettings.eyeHeightCm - EYE_HEIGHT_STEP_CM) },
                onIncrease = { onHorizonEyeHeightChange(horizonSettings.eyeHeightCm + EYE_HEIGHT_STEP_CM) },
            )

            SwitchRow(
                title = stringResource(R.string.horizon_shade_occluded),
                description = stringResource(R.string.horizon_shade_occluded_desc),
                checked = horizonSettings.shadeOccludedTrajectory,
                onCheckedChange = onHorizonShadeOccludedChange,
            )

            SwitchRow(
                title = stringResource(R.string.horizon_night_sky_real),
                description = stringResource(R.string.horizon_night_sky_real_desc),
                checked = horizonSettings.realHorizonInNightSky,
                onCheckedChange = onHorizonRealInNightSkyChange,
            )

            if (canDownloadTerrain) {
                SwitchRow(
                    title = stringResource(R.string.horizon_wifi_only),
                    description = stringResource(R.string.horizon_wifi_only_desc),
                    checked = horizonSettings.wifiOnly,
                    onCheckedChange = onHorizonWifiOnlyChange,
                )

                Button(
                    onClick = handleDownloadClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.horizon_download_now))
                }
            }

            AccordionRow(
                title = stringResource(R.string.horizon_import_title),
                expanded = manualImportExpanded,
                onExpandedChange = { manualImportExpanded = it },
            ) {
                OutlinedButton(
                    onClick = onImportTerrain,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text(stringResource(R.string.horizon_import_action))
                }

                if (missingTerrainTiles.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.horizon_import_body,
                            missingTerrainTiles.joinToString("\n") { "${it.fileName}.gz" },
                            "${DemTileDownloader.DEFAULT_BASE_URL}/skadi/",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }

            // Attribution is a licence condition of the elevation data, not a nicety.
            TextButton(onClick = { showAttribution = true }) {
                Text(stringResource(R.string.horizon_attribution_title))
            }
        }

        // ---- Section 4: Data Management ----
        SectionCard(
            title = stringResource(R.string.settings_section_storage),
        ) {
            // Row 0: Permanent offline data (Offline Regions)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_storage_offline_regions_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                val offlineText = pluralStringResource(
                    R.plurals.settings_storage_offline_regions_count,
                    storageMetrics.offlineRegionCount,
                    storageMetrics.demOfflineFormatted,
                    storageMetrics.offlineRegionCount,
                )
                Text(
                    text = offlineText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Action: Manage offline terrain
            OutlinedButton(
                onClick = onNavigateToOfflineTerrain,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 6.dp),
            ) {
                Icon(
                    imageVector = AppIcons.TerrainHorizon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_storage_manage_offline_button))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Row 1: Terrain Data Cache - Value right-aligned on new line
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_storage_terrain_cache_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(
                        R.string.settings_storage_terrain_cache_value,
                        storageMetrics.demCacheFormatted,
                        storageMetrics.demCacheLimitFormatted,
                    ),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Row 2: Computed Horizons - Value right-aligned on new line
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_storage_profiles_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                val profilesText = pluralStringResource(
                    R.plurals.settings_storage_profiles_count,
                    storageMetrics.profileCount,
                    storageMetrics.profilesFormatted,
                    storageMetrics.profileCount,
                )
                Text(
                    text = profilesText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Action: Clear terrain cache
            OutlinedButton(
                onClick = { showClearCacheConfirmation = true },
                enabled = storageMetrics.demCacheSizeBytes > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_storage_clear_cache_button))
            }
        }

        // ---- Section 5: Debug Options ----
        SectionHeader(
            text = stringResource(R.string.settings_section_debug),
            modifier = Modifier.padding(top = 8.dp),
        )

        SectionCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { debugAccordionExpanded = !debugAccordionExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_debug_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_debug_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = AppIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = debugChevronRotation },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = debugAccordionExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )

                    // ---- Group 1: Touch & Selection ----
                    Text(
                        text = stringResource(R.string.settings_debug_group_touch),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                    StepperRow(
                        title = stringResource(R.string.settings_debug_star_tap_radius),
                        description = stringResource(R.string.settings_debug_star_tap_radius_desc),
                        value = "${debugPreferences.starTapRadiusDp} dp",
                        canDecrease = debugPreferences.starTapRadiusDp > DebugPreferences.MIN_STAR_TAP_RADIUS_DP,
                        canIncrease = debugPreferences.starTapRadiusDp < DebugPreferences.MAX_STAR_TAP_RADIUS_DP,
                        onDecrease = {
                            val next = (debugPreferences.starTapRadiusDp - DebugPreferences.STEP_STAR_TAP_RADIUS_DP)
                                .coerceAtLeast(DebugPreferences.MIN_STAR_TAP_RADIUS_DP)
                            onDebugPreferencesChange(debugPreferences.copy(starTapRadiusDp = next))
                        },
                        onIncrease = {
                            val next = (debugPreferences.starTapRadiusDp + DebugPreferences.STEP_STAR_TAP_RADIUS_DP)
                                .coerceAtMost(DebugPreferences.MAX_STAR_TAP_RADIUS_DP)
                            onDebugPreferencesChange(debugPreferences.copy(starTapRadiusDp = next))
                        },
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_debug_show_tap_indicator),
                        description = stringResource(R.string.settings_debug_show_tap_indicator_desc),
                        checked = debugPreferences.showTapRadiusIndicator,
                        onCheckedChange = { onDebugPreferencesChange(debugPreferences.copy(showTapRadiusIndicator = it)) },
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    )

                    // ---- Group 2: Night Sky AR ----
                    Text(
                        text = stringResource(R.string.settings_debug_group_night_sky),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_debug_night_sky_calibration),
                        description = stringResource(R.string.settings_debug_night_sky_calibration_desc),
                        checked = debugPreferences.enableNightSkyCalibration,
                        onCheckedChange = { onDebugPreferencesChange(debugPreferences.copy(enableNightSkyCalibration = it)) },
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_debug_night_sky_focus_region),
                        description = stringResource(R.string.settings_debug_night_sky_focus_region_desc),
                        checked = debugPreferences.enableNightSkyFocusRegion,
                        onCheckedChange = { onDebugPreferencesChange(debugPreferences.copy(enableNightSkyFocusRegion = it)) },
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_debug_legacy_canvas_sky),
                        description = stringResource(R.string.settings_debug_legacy_canvas_sky_desc),
                        checked = debugPreferences.useLegacyCanvasSky,
                        onCheckedChange = { onDebugPreferencesChange(debugPreferences.copy(useLegacyCanvasSky = it)) },
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    )

                    // ---- Group 3: Terrain Horizon ----
                    Text(
                        text = stringResource(R.string.settings_debug_group_terrain),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_debug_terrain_metrics),
                        description = stringResource(R.string.settings_debug_terrain_metrics_desc),
                        checked = debugPreferences.showTerrainMetrics,
                        onCheckedChange = { onDebugPreferencesChange(debugPreferences.copy(showTerrainMetrics = it)) },
                    )
                }
            }
        }

        // Higher visible divider separating Version section
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(Modifier.height(8.dp))

        // ---- Section 5: Version Info (Verze) ----
        SectionHeader(
            text = stringResource(R.string.settings_section_version),
        )

        SectionCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_version_name_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 2.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_version_code_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = BuildConfig.VERSION_CODE.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showAttribution) {
        AlertDialog(
            onDismissRequest = { showAttribution = false },
            title = { Text(stringResource(R.string.horizon_attribution_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = stringResource(R.string.horizon_attribution_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAttribution = false }) {
                    Text(stringResource(R.string.action_dismiss))
                }
            },
        )
    }

    if (showStartTimeDialog) {
        TimePickerSettingDialog(
            title = stringResource(R.string.settings_dialog_title_start),
            initialTime = startTime,
            onConfirm = {
                onStartTimeChange(it)
                showStartTimeDialog = false
            },
            onDismiss = { showStartTimeDialog = false },
        )
    }

    if (showEndTimeDialog) {
        TimePickerSettingDialog(
            title = stringResource(R.string.settings_dialog_title_end),
            initialTime = endTime,
            onConfirm = {
                onEndTimeChange(it)
                showEndTimeDialog = false
            },
            onDismiss = { showEndTimeDialog = false },
        )
    }

    if (showConsentDialog) {
        HorizonConsentDialog(
            initialWifiOnly = horizonSettings.wifiOnly,
            onAllow = { wifiOnly ->
                showConsentDialog = false
                onHorizonNetworkConsent(true, wifiOnly)
                pendingActionAfterConsent?.invoke()
                pendingActionAfterConsent = null
            },
            onNever = {
                showConsentDialog = false
                onHorizonNetworkConsent(false, null)
                pendingActionAfterConsent?.invoke()
                pendingActionAfterConsent = null
            },
        )
    }

    if (showClearCacheConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirmation = false },
            title = { Text(stringResource(R.string.settings_storage_clear_dialog_title)) },
            text = { Text(stringResource(R.string.settings_storage_clear_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheConfirmation = false
                        onClearDemCache()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_storage_clear_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirmation = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun TimePickerRow(
    label: String,
    time: LocalTime,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                text = time.format(timeFormatter),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSettingDialog(
    title: String,
    initialTime: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val is24Hour = DateFormat.is24HourFormat(context)
    val state = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = is24Hour,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(LocalTime.of(state.hour, state.minute))
                },
            ) {
                Text(stringResource(R.string.settings_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
    )
}

/** One line saying what the terrain layer is doing, including when it is stuck and why. */
@Composable
private fun HorizonStatusRow(state: HorizonState) {
    val text = when (state) {
        is HorizonState.Idle -> null
        is HorizonState.Locating -> stringResource(R.string.horizon_status_locating)
        is HorizonState.NeedsTiles -> stringResource(R.string.horizon_status_needs_tiles)
        is HorizonState.Downloading -> stringResource(R.string.horizon_status_downloading, state.index, state.total)
        is HorizonState.Computing -> stringResource(R.string.horizon_status_computing)
        is HorizonState.Ready -> stringResource(R.string.horizon_status_ready)
        is HorizonState.Failed -> when (state.reason) {
            HorizonState.Failed.Reason.NETWORK -> stringResource(R.string.horizon_status_failed_network)
            HorizonState.Failed.Reason.METERED -> stringResource(R.string.horizon_status_failed_metered)
            HorizonState.Failed.Reason.STORAGE -> stringResource(R.string.horizon_status_failed_network)
            HorizonState.Failed.Reason.NO_DATA -> stringResource(R.string.horizon_status_failed_no_data)
            HorizonState.Failed.Reason.LOCATION -> stringResource(R.string.horizon_status_failed_location)
        }
    } ?: return

    val busy = state is HorizonState.Locating || state is HorizonState.Downloading || state is HorizonState.Computing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            if (state is HorizonState.Locating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                val progress = when (state) {
                    is HorizonState.Downloading -> state.progress
                    is HorizonState.Computing -> state.progress
                }
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (state is HorizonState.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun StepperRow(
    title: String,
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDecrease, enabled = canDecrease, modifier = Modifier.size(48.dp)) {
            Icon(AppIcons.Back, contentDescription = stringResource(R.string.horizon_step_down, title))
        }
        IconButton(onClick = onIncrease, enabled = canIncrease, modifier = Modifier.size(48.dp)) {
            Icon(AppIcons.Forward, contentDescription = stringResource(R.string.horizon_step_up, title))
        }
    }
}

private const val EYE_HEIGHT_STEP_CM = 5

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} kB"
    else -> "$bytes B"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThemeChoiceRow(
    currentTheme: NightVisionTheme,
    onThemeChange: (NightVisionTheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NightVisionTheme.entries.forEach { item ->
            val isSelected = item == currentTheme
            val themeColor = when (item) {
                NightVisionTheme.RED -> Color(0xFFFF1744)
                NightVisionTheme.AMBER -> Color(0xFFFFB300)
                NightVisionTheme.GREEN -> Color(0xFF00E676)
            }
            val labelRes = when (item) {
                NightVisionTheme.RED -> R.string.settings_theme_red
                NightVisionTheme.AMBER -> R.string.settings_theme_amber
                NightVisionTheme.GREEN -> R.string.settings_theme_green
            }

            FilledTonalToggleButton(
                checked = isSelected,
                onCheckedChange = { onThemeChange(item) },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics {
                        this.role = Role.RadioButton
                        this.selected = isSelected
                    },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(themeColor, CircleShape),
                    )
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
