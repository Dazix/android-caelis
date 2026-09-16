package com.dazix.caelis.ui.horizon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R
import com.dazix.caelis.data.horizon.DownloadEstimate
import com.dazix.caelis.data.horizon.OfflineDownloadState
import com.dazix.caelis.data.horizon.StorageMetrics
import com.dazix.caelis.domain.horizon.OfflineRegion
import com.dazix.caelis.domain.solar.GeoLocation
import com.dazix.caelis.ui.AppIcons
import com.dazix.caelis.ui.ScreenScaffold
import com.dazix.caelis.ui.SectionCard
import com.dazix.caelis.ui.SegmentedChoice

private val RADIUS_OPTIONS = listOf(50, 100, 150, 200)

@Composable
fun OfflineTerrainScreen(
    savedRegions: List<OfflineRegion>,
    downloadState: OfflineDownloadState,
    currentLocation: GeoLocation?,
    onEstimate: (Double, Double, Int) -> DownloadEstimate,
    onDownloadRegion: (name: String, lat: Double, lon: Double, radiusKm: Int) -> Unit,
    onCancelDownload: () -> Unit,
    onResetDownloadState: () -> Unit,
    onDeleteRegion: (String) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var latText by remember {
        mutableStateOf(currentLocation?.let { "%.4f".format(it.latitude).replace(',', '.') } ?: "")
    }
    var lonText by remember {
        mutableStateOf(currentLocation?.let { "%.4f".format(it.longitude).replace(',', '.') } ?: "")
    }
    var selectedRadius by remember { mutableIntStateOf(RADIUS_OPTIONS.first()) }
    var deleteCandidate by remember { mutableStateOf<OfflineRegion?>(null) }

    val parsedLat = remember(latText) { latText.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } }
    val parsedLon = remember(lonText) { lonText.toDoubleOrNull()?.takeIf { it in -180.0..180.0 } }
    val hasValidCoordinates = parsedLat != null && parsedLon != null

    val estimate = remember(parsedLat, parsedLon, selectedRadius) {
        if (parsedLat != null && parsedLon != null) {
            onEstimate(parsedLat, parsedLon, selectedRadius)
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it.replace(',', '.') },
                    label = { Text(stringResource(R.string.offline_terrain_lat_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !isDownloading,
                )

                OutlinedTextField(
                    value = lonText,
                    onValueChange = { lonText = it.replace(',', '.') },
                    label = { Text(stringResource(R.string.offline_terrain_lon_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !isDownloading,
                )
            }

            if (currentLocation != null) {
                OutlinedButton(
                    onClick = {
                        latText = "%.4f".format(currentLocation.latitude).replace(',', '.')
                        lonText = "%.4f".format(currentLocation.longitude).replace(',', '.')
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isDownloading,
                ) {
                    Icon(AppIcons.Compass, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.offline_terrain_use_current_location))
                }
            }

            Text(
                text = stringResource(R.string.offline_terrain_radius_label),
                style = MaterialTheme.typography.titleSmall,
            )

            SegmentedChoice(
                options = RADIUS_OPTIONS,
                selected = selectedRadius,
                onSelect = { selectedRadius = it },
                enabled = !isDownloading,
            ) { radius ->
                stringResource(R.string.offline_terrain_radius_km, radius)
            }

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
                        if (name.isNotBlank() && parsedLat != null && parsedLon != null) {
                            onDownloadRegion(name.trim(), parsedLat, parsedLon, selectedRadius)
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
