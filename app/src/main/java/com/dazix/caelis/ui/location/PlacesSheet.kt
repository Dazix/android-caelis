package com.dazix.caelis.ui.location

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R
import com.dazix.caelis.domain.location.SavedPlace
import com.dazix.caelis.domain.solar.CoordinateParseResult
import com.dazix.caelis.domain.solar.GeoCoordinateParser
import com.dazix.caelis.domain.solar.GeoLocation
import com.dazix.caelis.ui.AppIcons
import com.dazix.caelis.ui.CoordinateInputField
import com.dazix.caelis.ui.SwitchRow
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * Colours of the places sheet. Night Sky passes its night-vision palette so the sheet does not
 * break dark adaptation; Sun AR passes its gold-on-charcoal.
 */
data class PlacesSheetColors(
    val container: Color,
    val content: Color,
    val accent: Color,
    val scrim: Color = Color.Black.copy(alpha = 0.5f),
)

/**
 * The virtual places: choose GPS or a saved place for this screen, add, edit and delete places, and
 * whether both AR screens share the choice. A place is a name, coordinates in any format
 * [GeoCoordinateParser] reads and the zone its times are shown in.
 *
 * @param selectedId the place this screen shows, null for GPS.
 * @param gpsLocation offered as a one-tap fill for the coordinates, or null without a fix.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesSheet(
    places: List<SavedPlace>,
    selectedId: String?,
    syncEnabled: Boolean,
    gpsLocation: GeoLocation?,
    colors: PlacesSheetColors,
    onSelect: (String?) -> Unit,
    onSave: (SavedPlace) -> Unit,
    onDelete: (String) -> Unit,
    onSyncChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.container,
        contentColor = colors.content,
        scrimColor = colors.scrim,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.accent.copy(alpha = 0.4f)) },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        // The Material controls inside (radio buttons, switch, text fields) take their colours from
        // the theme; this one is derived from the screen's palette, so night vision stays intact.
        val base = MaterialTheme.colorScheme
        MaterialTheme(
            colorScheme = base.copy(
                primary = colors.accent,
                onPrimary = Color.Black,
                primaryContainer = colors.accent.copy(alpha = 0.22f),
                onPrimaryContainer = colors.content,
                surface = colors.container,
                onSurface = colors.content,
                surfaceVariant = colors.container,
                onSurfaceVariant = colors.content.copy(alpha = 0.72f),
                surfaceContainer = colors.container,
                surfaceContainerHigh = colors.container,
                surfaceContainerHighest = colors.container,
                outline = colors.content.copy(alpha = 0.45f),
                outlineVariant = colors.content.copy(alpha = 0.2f),
            ),
        ) {
            var editing by remember { mutableStateOf<SavedPlace?>(null) }
            var adding by rememberSaveable { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (adding || editing != null) {
                    PlaceEditor(
                        initial = editing,
                        gpsLocation = gpsLocation,
                        onSave = { place ->
                            onSave(place)
                            // A new place is what the user wants to look at.
                            if (editing == null) onSelect(place.id)
                            adding = false
                            editing = null
                        },
                        onCancel = {
                            adding = false
                            editing = null
                        },
                    )
                } else {
                    PlaceList(
                        places = places,
                        selectedId = selectedId,
                        syncEnabled = syncEnabled,
                        onSelect = onSelect,
                        onEdit = { editing = it },
                        onDelete = onDelete,
                        onAdd = { adding = true },
                        onSyncChange = onSyncChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceList(
    places: List<SavedPlace>,
    selectedId: String?,
    syncEnabled: Boolean,
    onSelect: (String?) -> Unit,
    onEdit: (SavedPlace) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    onSyncChange: (Boolean) -> Unit,
) {
    var deleteCandidate by remember { mutableStateOf<SavedPlace?>(null) }

    Text(
        text = stringResource(R.string.places_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = stringResource(R.string.places_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    PlaceRow(
        title = stringResource(R.string.places_gps),
        detail = null,
        selected = selectedId == null,
        onSelect = { onSelect(null) },
        icon = { Icon(AppIcons.Compass, contentDescription = null, modifier = Modifier.size(20.dp)) },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    if (places.isEmpty()) {
        Text(
            text = stringResource(R.string.places_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    places.forEach { place ->
        PlaceRow(
            title = place.name,
            detail = placeDetail(place),
            selected = place.id == selectedId,
            onSelect = { onSelect(place.id) },
            icon = { Icon(AppIcons.Place, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailing = {
                IconButton(onClick = { onEdit(place) }) {
                    Icon(AppIcons.Edit, contentDescription = stringResource(R.string.places_edit_named, place.name))
                }
                IconButton(onClick = { deleteCandidate = place }) {
                    Icon(
                        AppIcons.Delete,
                        contentDescription = stringResource(R.string.places_delete_named, place.name),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    }

    OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.places_add))
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SwitchRow(
        title = stringResource(R.string.places_sync_title),
        description = stringResource(R.string.places_sync_body),
        checked = syncEnabled,
        onCheckedChange = onSyncChange,
    )

    deleteCandidate?.let { place ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.places_delete_title, place.name)) },
            text = { Text(stringResource(R.string.places_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(place.id)
                    deleteCandidate = null
                }) {
                    Text(stringResource(R.string.places_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.places_cancel))
                }
            },
        )
    }
}

@Composable
private fun PlaceRow(
    title: String,
    detail: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    icon: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The row is the control; the radio button only shows its state.
        RadioButton(selected = selected, onClick = null)
        icon()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

private fun placeDetail(place: SavedPlace): String =
    "%s · %s".format(Locale.US, place.toGeoLocation().formatShort(), place.zoneId)

@Composable
private fun PlaceEditor(
    initial: SavedPlace?,
    gpsLocation: GeoLocation?,
    onSave: (SavedPlace) -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var coordsText by rememberSaveable(initial?.id) {
        mutableStateOf(initial?.let { "%.5f, %.5f".format(Locale.US, it.latitude, it.longitude) }.orEmpty())
    }
    var zoneId by rememberSaveable(initial?.id) { mutableStateOf(initial?.zoneId ?: ZoneId.systemDefault().id) }
    var pickingZone by remember { mutableStateOf(false) }

    val parseResult = remember(coordsText) { GeoCoordinateParser.parse(coordsText) }
    val parsed = (parseResult as? CoordinateParseResult.Success)?.location
    val canSave = name.isNotBlank() && parsed != null

    Text(
        text = stringResource(if (initial == null) R.string.places_add else R.string.places_edit),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.places_name_label)) },
        placeholder = { Text(stringResource(R.string.places_name_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    CoordinateInputField(
        text = coordsText,
        onTextChange = { coordsText = it },
        parseResult = parseResult,
        currentLocation = gpsLocation,
    )
    OutlinedTextField(
        value = zoneId,
        onValueChange = {},
        readOnly = true,
        label = { Text(stringResource(R.string.places_zone_label)) },
        supportingText = { Text(stringResource(R.string.places_zone_hint)) },
        trailingIcon = {
            IconButton(onClick = { pickingZone = true }) {
                Icon(AppIcons.Search, contentDescription = stringResource(R.string.places_zone_search))
            }
        },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { pickingZone = true },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.places_cancel)) }
        Button(
            enabled = canSave,
            onClick = {
                val location = parsed ?: return@Button
                onSave(
                    SavedPlace(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        name = name.trim(),
                        latitude = location.latitude,
                        longitude = location.longitude,
                        zoneId = zoneId,
                        createdAtMs = initial?.createdAtMs ?: System.currentTimeMillis(),
                    ),
                )
            },
        ) { Text(stringResource(R.string.places_save)) }
    }
    Spacer(Modifier.size(4.dp))

    if (pickingZone) {
        ZonePickerDialog(
            selected = zoneId,
            onPick = {
                zoneId = it
                pickingZone = false
            },
            onDismiss = { pickingZone = false },
        )
    }
}

/** Every zone this runtime knows, searchable by any part of its id ("Prague", "America/", "UTC"). */
@Composable
private fun ZonePickerDialog(
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val allZones = remember { ZoneId.getAvailableZoneIds().sorted() }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        val q = query.trim().replace(' ', '_')
        if (q.isEmpty()) allZones else allZones.filter { it.contains(q, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.places_zone_label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.places_zone_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(filtered, key = { it }) { zone ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = zone == selected, onClick = { onPick(zone) }, role = Role.RadioButton),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = zone == selected, onClick = null)
                            Text(zone, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.places_cancel)) }
        },
    )
}
