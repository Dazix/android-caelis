package com.dazix.caelis.ui.horizon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.dazix.caelis.R
import com.dazix.caelis.data.horizon.DownloadEstimate
import com.dazix.caelis.data.horizon.OfflineDownloadState
import com.dazix.caelis.data.horizon.StorageMetrics
import com.dazix.caelis.domain.horizon.OfflineRegion
import com.dazix.caelis.domain.solar.CoordinateParseResult
import com.dazix.caelis.domain.solar.GeoCoordinateParser
import com.dazix.caelis.domain.solar.GeoLocation
import com.dazix.caelis.ui.AppIcons
import com.dazix.caelis.ui.CoordinateInputField
import com.dazix.caelis.ui.ExpressiveMotion
import com.dazix.caelis.ui.ScreenScaffold
import com.dazix.caelis.ui.SectionCard
import java.util.Locale

private val RADIUS_OPTIONS = listOf(50, 100, 150, 200)

/** A new region handed over from elsewhere (a place shared from a map app) to fill the form with. */
data class OfflineTerrainPrefill(val name: String, val location: GeoLocation)

@Composable
fun OfflineTerrainScreen(
    savedRegions: List<OfflineRegion>,
    downloadState: OfflineDownloadState,
    currentLocation: GeoLocation?,
    prefill: OfflineTerrainPrefill?,
    onPrefillConsumed: () -> Unit,
    onEstimate: (Double, Double, Int) -> DownloadEstimate,
    onDownloadRegion: (name: String, lat: Double, lon: Double, radiusKm: Int) -> Unit,
    onCancelDownload: () -> Unit,
    onResetDownloadState: () -> Unit,
    onDeleteRegion: (String) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var coordsText by remember {
        mutableStateOf(currentLocation?.let { "%.4f, %.4f".format(it.latitude, it.longitude) } ?: "")
    }
    var selectedRadius by remember { mutableIntStateOf(RADIUS_OPTIONS.first()) }
    var deleteCandidate by remember { mutableStateOf<OfflineRegion?>(null) }

    // Taken once and cleared, so going back and forth does not overwrite what the user typed since.
    LaunchedEffect(prefill) {
        if (prefill != null) {
            name = prefill.name
            coordsText = "%.5f, %.5f".format(Locale.US, prefill.location.latitude, prefill.location.longitude)
            onPrefillConsumed()
        }
    }

    val parseResult = remember(coordsText) { GeoCoordinateParser.parse(coordsText) }
    val parsedLocation = (parseResult as? CoordinateParseResult.Success)?.location
    val hasValidCoordinates = parsedLocation != null

    val estimate = remember(parsedLocation, selectedRadius) {
        if (parsedLocation != null) {
            onEstimate(parsedLocation.latitude, parsedLocation.longitude, selectedRadius)
        } else {
            null
        }
    }

    val isDownloading = downloadState is OfflineDownloadState.Downloading

    ScreenScaffold(
        title = stringResource(R.string.offline_terrain_title),
        subtitle = stringResource(R.string.offline_terrain_subtitle),
        onBack = onBack,
    ) {
        // Section 1: New Region Input Form
        SectionCard(title = stringResource(R.string.offline_terrain_new_region)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.offline_terrain_name_label)) },
                placeholder = { Text(stringResource(R.string.offline_terrain_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isDownloading,
            )

            CoordinateInputField(
                text = coordsText,
                onTextChange = { coordsText = it },
                parseResult = parseResult,
                currentLocation = currentLocation,
                enabled = !isDownloading,
            )

            Text(
                text = stringResource(R.string.offline_terrain_radius_label),
                style = MaterialTheme.typography.titleSmall,
            )

            RadiusSelector(
                options = RADIUS_OPTIONS,
                selected = selectedRadius,
                onSelect = { selectedRadius = it },
                enabled = !isDownloading,
            )

            // Size estimator preview
            if (estimate != null) {
                val estimateText = if (estimate.needNetworkDownload.isEmpty()) {
                    stringResource(
                        R.string.offline_terrain_estimate_all_cached,
                        estimate.requiredTiles.size,
                        StorageMetrics.formatBytes(estimate.estimatedTotalBytes),
                    )
                } else {
                    stringResource(
                        R.string.offline_terrain_estimate_summary,
                        estimate.requiredTiles.size,
                        StorageMetrics.formatBytes(estimate.estimatedTotalBytes),
                        StorageMetrics.formatBytes(estimate.estimatedNetworkBytes),
                    )
                }

                Text(
                    text = estimateText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            // Download progress or download trigger button
            if (downloadState is OfflineDownloadState.Downloading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.offline_terrain_downloading,
                            downloadState.regionName,
                            downloadState.tileIndex,
                            downloadState.totalTiles,
                            (downloadState.overallProgress * 100).toInt(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { downloadState.overallProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = onCancelDownload,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.offline_terrain_cancel_download))
                    }
                }
            } else {
                Button(
                    onClick = {
                        if (name.isNotBlank() && parsedLocation != null) {
                            onDownloadRegion(name.trim(), parsedLocation.latitude, parsedLocation.longitude, selectedRadius)
                        }
                    },
                    enabled = name.isNotBlank() && hasValidCoordinates,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(AppIcons.TerrainHorizon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.offline_terrain_download_btn))
                }
            }

            // Feedback messages
            when (downloadState) {
                is OfflineDownloadState.Success -> {
                    Text(
                        text = stringResource(R.string.offline_terrain_download_complete, downloadState.region.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                is OfflineDownloadState.Failed -> {
                    Text(
                        text = stringResource(R.string.offline_terrain_download_failed, downloadState.error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                else -> Unit
            }
        }

        // Section 2: Saved Offline Regions List
        SectionCard(title = stringResource(R.string.offline_terrain_saved_regions_title)) {
            if (savedRegions.isEmpty()) {
                Text(
                    text = stringResource(R.string.offline_terrain_saved_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                savedRegions.forEachIndexed { index, region ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = region.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.offline_terrain_region_details,
                                    region.targetLat,
                                    region.targetLon,
                                    region.radiusKm,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalContentColor.current.copy(alpha = 0.75f),
                            )
                            Text(
                                text = stringResource(
                                    R.string.offline_terrain_region_tiles_info,
                                    region.requiredTiles.size,
                                    StorageMetrics.formatBytes(region.totalSizeBytes),
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        IconButton(
                            onClick = { deleteCandidate = region },
                            enabled = !isDownloading,
                        ) {
                            Icon(
                                imageVector = AppIcons.Delete,
                                contentDescription = stringResource(R.string.offline_terrain_delete_confirm),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialog for region deletion
    deleteCandidate?.let { region ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.offline_terrain_delete_title)) },
            text = {
                Text(stringResource(R.string.offline_terrain_delete_message, region.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteRegion(region.id)
                        deleteCandidate = null
                    },
                ) {
                    Text(stringResource(R.string.offline_terrain_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.offline_terrain_delete_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RadiusSelector(
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { radius ->
            val isSelected = radius == selected
            val containerColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                animationSpec = ExpressiveMotion.effects(),
                label = "radiusContainerColor",
            )
            val contentColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = ExpressiveMotion.effects(),
                label = "radiusContentColor",
            )

            Surface(
                selected = isSelected,
                onClick = { onSelect(radius) },
                enabled = enabled,
                shape = MaterialTheme.shapes.medium,
                color = containerColor,
                contentColor = contentColor,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics {
                        this.role = Role.RadioButton
                        this.selected = isSelected
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$radius\nkm",
                        style = MaterialTheme.typography.labelLargeEmphasized.copy(
                            lineHeight = 0.8.em,
                            lineHeightStyle = LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both,
                            ),
                        ),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
