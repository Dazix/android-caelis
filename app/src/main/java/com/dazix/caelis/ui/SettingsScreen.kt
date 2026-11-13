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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R
import com.dazix.caelis.domain.horizon.DownloadMode
import com.dazix.caelis.ui.horizon.TerrainDownloadOfferRow
import com.dazix.caelis.domain.nightsky.NightVisionActivationMode
import com.dazix.caelis.data.DebugPreferences
import com.dazix.caelis.ar.pose.ArCorePresence
import com.dazix.caelis.data.ArCoreSettings
import com.dazix.caelis.data.HorizonSettings
import com.dazix.caelis.data.horizon.DemTileDownloader
import com.dazix.caelis.data.horizon.HorizonState
import com.dazix.caelis.data.horizon.StorageMetrics
import com.dazix.caelis.data.horizon.sizeText
import com.dazix.caelis.domain.horizon.DemTileKey
import com.dazix.caelis.domain.nightsky.NightVisionTheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

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
    /** Automatic or on demand, for a user who has consented. Never is a refused consent. */
    onHorizonAutoDownloadChange: (Boolean) -> Unit = {},
    onHorizonConfirmLimitChange: (Int) -> Unit = {},
    /** Tap on Download for a run waiting for confirmation. */
    onConfirmTerrainDownload: () -> Unit = {},
    onHorizonShadeOccludedChange: (Boolean) -> Unit = {},
    onHorizonWifiOnlyChange: (Boolean) -> Unit = {},
    onHorizonRealInNightSkyChange: (Boolean) -> Unit = {},
    /** granted, Wi-Fi only (null: unchanged), automatic download (null: unchanged). */
    onHorizonNetworkConsent: (Boolean, Boolean?, Boolean?) -> Unit = { _, _, _ -> },
    onDownloadTerrain: () -> Unit = {},
    onImportTerrain: () -> Unit = {},
    onClearTerrainCache: () -> Unit = {},
    onClearDemCache: () -> Unit = onClearTerrainCache,
    onClearProfiles: () -> Unit = {},
    arCoreSettings: ArCoreSettings = ArCoreSettings(),
    /** False in a build made without a Geospatial API key: the switch is then not shown at all. */
    geospatialAvailable: Boolean = false,
    onArCoreConsentChange: (Boolean) -> Unit = {},
    onGeospatialConsentChange: (Boolean) -> Unit = {},
    onStartLocationUpdates: () -> Unit = {},
    onStopLocationUpdates: () -> Unit = {},
    onRefreshLocation: () -> Unit = {},
    onThemeChange: (NightVisionTheme) -> Unit,
    onActivationModeChange: (NightVisionActivationMode) -> Unit,
    onStartTimeChange: (LocalTime) -> Unit,
    onEndTimeChange: (LocalTime) -> Unit,
    onDebugPreferencesChange: (DebugPreferences) -> Unit = {},
    onNavigateToOfflineTerrain: () -> Unit = {},
    /** Arrived from an AR screen's "data missing" chip: scroll to the download, then report it done. */
    focusTerrainDownload: Boolean = false,
    onTerrainDownloadFocused: () -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showStartTimeDialog by remember { mutableStateOf(false) }
    var showEndTimeDialog by remember { mutableStateOf(false) }
    var showAttribution by remember { mutableStateOf(false) }
    var showConsentDialog by remember { mutableStateOf(false) }
    var showClearCacheConfirmation by remember { mutableStateOf(false) }
    var showClearProfilesConfirmation by remember { mutableStateOf(false) }
    var showArCoreConsentDialog by remember { mutableStateOf(false) }
    var showGeospatialConsentDialog by remember { mutableStateOf(false) }
    var pendingActionAfterConsent by remember { mutableStateOf<(() -> Unit)?>(null) }
    // The mode picked while consent was not given yet; applied by the dialog's Allow, not before it.
    var pendingAutoDownload by remember { mutableStateOf<Boolean?>(null) }
    var pendingLocationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var manualImportExpanded by remember { mutableStateOf(false) }
    val terrainDownloadRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(focusTerrainDownload) {
        if (!focusTerrainDownload) return@LaunchedEffect
        // Without a network the import is the only way to get the data, so open it as well.
        if (!canDownloadTerrain) manualImportExpanded = true
        // The block has to be laid out before it can be brought into view.
        withFrameNanos { }
        terrainDownloadRequester.bringIntoView()
        onTerrainDownloadFocused()
    }
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

    // "Download now" asks again after a "no": the button is an explicit request for exactly what was
    // refused, and without the question it only ended, silently, in "tiles missing". The question
    // still never comes back on its own.
    val handleDownloadClick: () -> Unit = {
        if (!horizonSettings.networkDownloadAllowed && canDownloadTerrain) {
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

            HorizonStatusRow(state = horizonState, onConfirmDownload = onConfirmTerrainDownload)

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

            // One block, so "scroll to the download" lands on the button and the import together.
            Column(
                modifier = Modifier.bringIntoViewRequester(terrainDownloadRequester),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (canDownloadTerrain) {
                    DownloadModeRow(
                        mode = horizonSettings.downloadMode,
                        onModeChange = { mode ->
                            when {
                                mode == DownloadMode.NEVER -> onHorizonNetworkConsent(false, null, null)
                                horizonSettings.networkDownloadAllowed ->
                                    onHorizonAutoDownloadChange(mode == DownloadMode.AUTOMATIC)
                                else -> {
                                    // Leaving Never is consenting again, so the dialog says what that means first.
                                    pendingAutoDownload = mode == DownloadMode.AUTOMATIC
                                    showConsentDialog = true
                                }
                            }
                        },
                    )

                    if (horizonSettings.downloadMode == DownloadMode.AUTOMATIC) {
                        ConfirmLimitSlider(
                            limitMb = horizonSettings.confirmAboveMb,
                            onLimitChange = onHorizonConfirmLimitChange,
                        )
                    }

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
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

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

            // Action: Clear terrain cache, under the row it empties.
            OutlinedButton(
                onClick = { showClearCacheConfirmation = true },
                enabled = storageMetrics.demCacheSizeBytes > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 6.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_storage_clear_cache_button))
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

            // Action: Clear computed horizons. Separate from the cache on purpose: the profiles are
            // what works with no signal, and a place without cached tiles cannot get one back
            // without a new download.
            OutlinedButton(
                onClick = { showClearProfilesConfirmation = true },
                enabled = storageMetrics.profileCount > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_storage_clear_profiles_button))
            }
        }

        // ---- ARCore (Google Play Services for AR) ----
        //
        // Nothing here touches ARCore: asking Play Services whether this phone is supported is
        // already contact with Google, so that check waits for consent. Whether the package is
        // installed comes from the local package list (ArCorePresence), which contacts no one.
        // Turning a switch ON goes through a dialog that says what Google's component sends;
        // turning it OFF is immediate and needs no dialog.
        SectionHeader(
            text = stringResource(R.string.arcore_section_title),
            modifier = Modifier.padding(top = 8.dp),
        )

        SectionCard {
            // Re-read on every return to the screen: the user may have installed, updated or
            // disabled the component in the meantime.
            var arCorePresence by remember { mutableStateOf<ArCorePresence?>(null) }
            LifecycleResumeEffect(Unit) {
                arCorePresence = ArCorePresence.read(context.packageManager)
                onPauseOrDispose { }
            }
            arCorePresence?.let { ArCorePresenceRow(it) }

            SwitchRow(
                title = stringResource(R.string.arcore_enabled),
                description = stringResource(R.string.arcore_enabled_desc),
                checked = arCoreSettings.arCoreConsent,
                onCheckedChange = { checked ->
                    if (checked) showArCoreConsentDialog = true else onArCoreConsentChange(false)
                },
            )

            // The switch is not offered at all in a build without a key, rather than shown dead:
            // there is nothing the user could do to make it work.
            if (geospatialAvailable) {
                SwitchRow(
                    title = stringResource(R.string.arcore_geospatial_enabled),
                    description = stringResource(R.string.arcore_geospatial_desc),
                    checked = arCoreSettings.geospatialConsent,
                    enabled = arCoreSettings.arCoreConsent,
                    disabledReason = stringResource(R.string.arcore_geospatial_needs_arcore),
                    onCheckedChange = { checked ->
                        if (checked) showGeospatialConsentDialog = true else onGeospatialConsentChange(false)
                    },
                )
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
                        title = stringResource(R.string.settings_debug_night_sky_focus_region),
                        description = stringResource(R.string.settings_debug_night_sky_focus_region_desc),
                        checked = debugPreferences.enableNightSkyFocusRegion,
                        onCheckedChange = { onDebugPreferencesChange(debugPreferences.copy(enableNightSkyFocusRegion = it)) },
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

                    // Debug builds only: the log carries the field expected at the user's position.
                    if (BuildConfig.DEBUG) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        )

                        // ---- Group 4: Compass ----
                        Text(
                            text = stringResource(R.string.settings_debug_group_compass),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        )
                        SwitchRow(
                            title = stringResource(R.string.settings_debug_log_compass),
                            description = stringResource(R.string.settings_debug_log_compass_desc),
                            checked = debugPreferences.logCompass,
                            onCheckedChange = { onDebugPreferencesChange(debugPreferences.copy(logCompass = it)) },
                        )
                    }
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
                onHorizonNetworkConsent(true, wifiOnly, pendingAutoDownload)
                pendingAutoDownload = null
                pendingActionAfterConsent?.invoke()
                pendingActionAfterConsent = null
            },
            onNever = {
                showConsentDialog = false
                onHorizonNetworkConsent(false, null, null)
                pendingAutoDownload = null
                pendingActionAfterConsent?.invoke()
                pendingActionAfterConsent = null
            },
        )
    }

    if (showArCoreConsentDialog) {
        ArCoreConsentDialog(
            title = stringResource(R.string.arcore_consent_title),
            body = stringResource(R.string.arcore_consent_body),
            onAllow = {
                showArCoreConsentDialog = false
                onArCoreConsentChange(true)
            },
            onCancel = { showArCoreConsentDialog = false },
        )
    }

    if (showGeospatialConsentDialog) {
        ArCoreConsentDialog(
            title = stringResource(R.string.geospatial_consent_title),
            body = stringResource(R.string.geospatial_consent_body),
            onAllow = {
                showGeospatialConsentDialog = false
                onGeospatialConsentChange(true)
            },
            onCancel = { showGeospatialConsentDialog = false },
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

    if (showClearProfilesConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearProfilesConfirmation = false },
            title = { Text(stringResource(R.string.settings_storage_clear_profiles_dialog_title)) },
            text = { Text(stringResource(R.string.settings_storage_clear_profiles_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearProfilesConfirmation = false
                        onClearProfiles()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_storage_clear_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearProfilesConfirmation = false }) {
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

/**
 * The disclosure shown before ARCore or the Geospatial API is allowed. Cancelling is a real answer
 * (nothing changes) and so is tapping outside, unlike the terrain consent that has to be answered:
 * here "no" is the state the app is already in.
 */
@Composable
private fun ArCoreConsentDialog(
    title: String,
    body: String,
    onAllow: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.arcore_consent_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.arcore_consent_cancel))
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

/**
 * Whether Google Play Services for AR is on the phone, so the switch is not a mystery: on a Pixel
 * it ships with the system and nothing has to be installed; elsewhere it may be missing, and this
 * app never installs it.
 */
@Composable
private fun ArCorePresenceRow(presence: ArCorePresence) {
    val (icon, text) = when (presence) {
        is ArCorePresence.Installed -> AppIcons.Check to if (presence.preinstalled) {
            stringResource(R.string.arcore_presence_preinstalled, presence.versionName)
        } else {
            stringResource(R.string.arcore_presence_installed, presence.versionName)
        }
        ArCorePresence.NotInstalled -> AppIcons.Info to stringResource(R.string.arcore_presence_missing)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The text says it all; the icon only repeats it.
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One line saying what the terrain layer is doing, including when it is stuck and why. */
@Composable
private fun HorizonStatusRow(state: HorizonState, onConfirmDownload: () -> Unit) {
    if (state is HorizonState.AwaitingConfirmation) {
        TerrainDownloadOfferRow(state = state, onDownload = onConfirmDownload)
        return
    }
    val text = when (state) {
        is HorizonState.Idle -> null
        is HorizonState.AwaitingConfirmation -> null
        is HorizonState.Sizing -> stringResource(R.string.horizon_status_sizing)
        is HorizonState.Locating -> stringResource(
            if (state.satellites) R.string.horizon_status_locating else R.string.horizon_status_locating_network,
        )
        is HorizonState.NeedsTiles -> stringResource(R.string.horizon_status_needs_tiles)
        is HorizonState.Downloading -> stringResource(
            R.string.horizon_status_downloading,
            state.index,
            state.total,
            state.sizeText(LocalContext.current),
        )
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

    val busy = state is HorizonState.Locating || state is HorizonState.Sizing ||
        state is HorizonState.Downloading || state is HorizonState.Computing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            if (state is HorizonState.Locating || state is HorizonState.Sizing) {
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

/**
 * The automatic mode's confirmation limit on a slider through [HorizonSettings.CONFIRM_LIMIT_CHOICES_MB].
 * The value follows the thumb while dragging and is saved once on release, not per step. The released
 * value stays shown until the store has it: the save comes back a few frames later, and letting go
 * of it at once put the thumb back on the old value for those frames.
 */
@Composable
private fun ConfirmLimitSlider(limitMb: Int, onLimitChange: (Int) -> Unit) {
    val choices = HorizonSettings.CONFIRM_LIMIT_CHOICES_MB
    val storedIndex = choices.indexOf(limitMb).takeIf { it >= 0 } ?: choices.lastIndex
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var savingIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(storedIndex) { if (savingIndex == storedIndex) savingIndex = null }
    val index = dragIndex ?: savingIndex ?: storedIndex
    val label = stringResource(R.string.horizon_confirm_limit)
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                text = if (choices[index] == HorizonSettings.CONFIRM_UNLIMITED) {
                    stringResource(R.string.horizon_confirm_limit_unlimited)
                } else {
                    stringResource(R.string.horizon_confirm_limit_value, choices[index])
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = index.toFloat(),
            onValueChange = { dragIndex = it.roundToInt().coerceIn(0, choices.lastIndex) },
            onValueChangeFinished = {
                dragIndex?.takeIf { it != storedIndex }?.let {
                    savingIndex = it
                    onLimitChange(choices[it])
                }
                dragIndex = null
            },
            valueRange = 0f..choices.lastIndex.toFloat(),
            steps = choices.size - 2,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
        )
    }
}

/** Automatic / On demand / Never, with what the chosen one does in a sentence under the chips. */
@Composable
private fun DownloadModeRow(mode: DownloadMode, onModeChange: (DownloadMode) -> Unit) {
    Text(
        text = stringResource(R.string.horizon_download_mode),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            DownloadMode.AUTOMATIC to R.string.horizon_download_mode_auto,
            DownloadMode.ON_DEMAND to R.string.horizon_download_mode_on_demand,
            DownloadMode.NEVER to R.string.horizon_download_mode_never,
        ).forEach { (choice, label) ->
            FilterChip(
                selected = mode == choice,
                onClick = { if (mode != choice) onModeChange(choice) },
                label = { Text(stringResource(label)) },
            )
        }
    }
    Text(
        text = stringResource(
            when (mode) {
                DownloadMode.AUTOMATIC -> R.string.horizon_download_mode_auto_desc
                DownloadMode.ON_DEMAND -> R.string.horizon_download_mode_on_demand_desc
                DownloadMode.NEVER -> R.string.horizon_download_mode_never_desc
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

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
