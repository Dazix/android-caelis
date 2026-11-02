package com.dazix.caelis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R
import com.dazix.caelis.domain.solar.CoordinateFormat
import com.dazix.caelis.domain.solar.CoordinateParseResult
import com.dazix.caelis.domain.solar.GeoLocation

/**
 * One free-text field for a position in any format `GeoCoordinateParser` reads (decimal degrees,
 * DMS, DDM, a map link), with what it understood shown underneath: the format and the parsed
 * coordinates, or why it could not, with examples. Shared by Offline terrain and the virtual places.
 *
 * @param parseResult the caller parses ([text] is its input), because it also needs the location.
 * @param currentLocation offered as a one-tap fill while the field is empty, or null for none.
 */
@Composable
fun CoordinateInputField(
    text: String,
    onTextChange: (String) -> Unit,
    parseResult: CoordinateParseResult,
    currentLocation: GeoLocation?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text(stringResource(R.string.offline_terrain_coords_label)) },
            placeholder = {
                Text(
                    text = stringResource(R.string.offline_terrain_coords_hint),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            isError = parseResult is CoordinateParseResult.Failure && parseResult !is CoordinateParseResult.Failure.Empty,
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { onTextChange("") }, enabled = enabled) {
                        Icon(AppIcons.Clear, contentDescription = stringResource(R.string.action_clear))
                    }
                } else if (currentLocation != null) {
                    IconButton(
                        onClick = {
                            onTextChange("%.4f, %.4f".format(java.util.Locale.US, currentLocation.latitude, currentLocation.longitude))
                        },
                        enabled = enabled,
                    ) {
                        Icon(AppIcons.Compass, contentDescription = stringResource(R.string.offline_terrain_use_current_location))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
        )

        // Parser status: the format it recognised and the result, or why not.
        when (parseResult) {
            is CoordinateParseResult.Success -> {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        val formatBadge = when (parseResult.format) {
                            CoordinateFormat.DECIMAL_DEGREES -> stringResource(R.string.offline_terrain_format_dd)
                            CoordinateFormat.DEGREES_MINUTES_SECONDS -> stringResource(R.string.offline_terrain_format_dms)
                            CoordinateFormat.DEGREES_DECIMAL_MINUTES -> stringResource(R.string.offline_terrain_format_ddm)
                            CoordinateFormat.MAP_LINK -> stringResource(R.string.offline_terrain_format_link)
                        }
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Text(
                                text = formatBadge,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.offline_terrain_coords_parsed_preview,
                                parseResult.location.latitude,
                                parseResult.location.longitude,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            is CoordinateParseResult.Failure.OutOfBounds -> {
                Text(
                    text = stringResource(R.string.offline_terrain_coords_out_of_bounds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            is CoordinateParseResult.Failure.InvalidFormat -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.offline_terrain_coords_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.offline_terrain_coords_examples_title) + ":\n" +
                            "• DD: " + stringResource(R.string.offline_terrain_coords_example_dd) + "\n" +
                            "• DMS: " + stringResource(R.string.offline_terrain_coords_example_dms) + "\n" +
                            "• DDM: " + stringResource(R.string.offline_terrain_coords_example_ddm),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is CoordinateParseResult.Failure.Empty -> {
                Text(
                    text = "• DD: " + stringResource(R.string.offline_terrain_coords_example_dd) + "\n" +
                        "• DMS: " + stringResource(R.string.offline_terrain_coords_example_dms) + "\n" +
                        "• DDM: " + stringResource(R.string.offline_terrain_coords_example_ddm),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}
