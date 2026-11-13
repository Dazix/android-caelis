package com.dazix.caelis.ui.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R
import com.dazix.caelis.domain.location.IncomingPlace
import com.dazix.caelis.domain.location.SavedPlace
import com.dazix.caelis.domain.solar.CoordinateParseResult
import com.dazix.caelis.domain.solar.GeoCoordinateParser
import com.dazix.caelis.domain.solar.GeoLocation
import com.dazix.caelis.ui.CoordinateInputField
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * A place another app handed over (share sheet, `geo:` link), and the two things it can become: a
 * saved virtual place, or an offline terrain region downloaded ahead of a trip. Every field stays
 * editable, so a short link or a text with no position still ends in a usable form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingPlaceSheet(
    incoming: IncomingPlace,
    gpsLocation: GeoLocation?,
    onSavePlace: (SavedPlace) -> Unit,
    onPrefetch: (name: String, location: GeoLocation) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Keyed on the hand-over, so a second share while the sheet is open replaces the form.
        var name by remember(incoming) { mutableStateOf(incoming.suggestedName.orEmpty()) }
        var coordsText by remember(incoming) {
            mutableStateOf(
                (incoming as? IncomingPlace.Found)?.location
                    ?.let { "%.5f, %.5f".format(Locale.US, it.latitude, it.longitude) }
                    .orEmpty(),
            )
        }
        var zoneId by remember(incoming) { mutableStateOf(ZoneId.systemDefault().id) }

        val parseResult = remember(coordsText) { GeoCoordinateParser.parse(coordsText) }
        val parsed = (parseResult as? CoordinateParseResult.Success)?.location

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.incoming_place_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            val problem = when (incoming) {
                is IncomingPlace.Found -> null
                is IncomingPlace.ShortLink -> R.string.incoming_place_short_link
                is IncomingPlace.NotFound -> R.string.incoming_place_not_found
            }
            Text(
                text = stringResource(problem ?: R.string.incoming_place_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
            ZoneField(zoneId = zoneId, onZoneChange = { zoneId = it })

            Button(
                enabled = name.isNotBlank() && parsed != null,
                onClick = {
                    val location = parsed ?: return@Button
                    onSavePlace(
                        SavedPlace(
                            id = UUID.randomUUID().toString(),
                            name = name.trim(),
                            latitude = location.latitude,
                            longitude = location.longitude,
                            zoneId = zoneId,
                            createdAtMs = System.currentTimeMillis(),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.incoming_place_save)) }
            Text(
                text = stringResource(R.string.incoming_place_save_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                enabled = parsed != null,
                onClick = { parsed?.let { onPrefetch(name.trim(), it) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.incoming_place_prefetch)) }
            Text(
                text = stringResource(R.string.incoming_place_prefetch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.places_cancel))
            }
        }
    }
}
