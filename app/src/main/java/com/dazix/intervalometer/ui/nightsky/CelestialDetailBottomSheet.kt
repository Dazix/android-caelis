package com.dazix.intervalometer.ui.nightsky

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dazix.intervalometer.R
import com.dazix.intervalometer.ar.RedPrimary
import com.dazix.intervalometer.ar.RedSecondary
import com.dazix.intervalometer.domain.nightsky.CelestialBodyDetails
import com.dazix.intervalometer.ui.AppIcons
import java.util.Locale
import kotlin.math.abs

/**
 * Material 3 Expressive draggable bottom sheet content displaying rich astronomical details of a selected celestial object.
 * Designed to be embedded in [androidx.compose.material3.BottomSheetScaffold].
 *
 * In PartiallyExpanded (peek) state, displays the Drag Handle and Header Row (Name, Magnitude badge, Type pill, Dismiss button).
 * In Expanded state, smoothly reveals the complete astrophysics & equatorial coordinate data grid.
 * Fully complies with Red Vision (Astrophotography Night Mode).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CelestialDetailSheetContent(
    selectedBody: CelestialBodyDetails?,
    redVisionMode: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selectedBody == null) {
        Spacer(modifier = Modifier.height(1.dp))
        return
    }

    val titleColor = if (redVisionMode) RedPrimary else Color.White
    val labelColor = if (redVisionMode) RedSecondary else Color(0xFF8C9BAE)
    val valueColor = if (redVisionMode) RedPrimary else Color(0xFFE2E8F0)
    val accentColor = if (redVisionMode) RedPrimary else Color(0xFF4DD0E1)
    val badgeContainer = if (redVisionMode) Color(0xFF330000) else Color(0xFF1B2334)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        // Drag Handle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            BottomSheetDefaults.DragHandle(
                color = if (redVisionMode) RedPrimary.copy(alpha = 0.6f) else Color(0xFF718096),
            )
        }

        // Header Row: Object Name, Magnitude/Type Badges, and Dismiss Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selectedBody.name,
                    color = titleColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Magnitude Badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeContainer,
                        border = BorderStroke(0.8.dp, accentColor.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            text = String.format(Locale.US, "V %+.2f", selectedBody.magnitude),
                            color = accentColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }

                    // Body Type Pill
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeContainer,
                    ) {
                        Text(
                            text = selectedBody.type,
                            color = valueColor.copy(alpha = 0.9f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }

                    if (selectedBody.constellation != null) {
                        Text(
                            text = "· ${selectedBody.constellation}",
                            color = labelColor,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // Close Button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Clear,
                    contentDescription = stringResource(R.string.celestial_close),
                    tint = labelColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.8.dp)
                .background(if (redVisionMode) RedPrimary.copy(alpha = 0.25f) else Color(0xFF283248)),
        )

        // Expanded Section: Detailed Astrophysics & Celestial Coordinates Table
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Data Grid Row 1: Constellation & Distance
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CelestialDataField(
                    label = stringResource(R.string.celestial_constellation),
                    value = selectedBody.constellation ?: "—",
                    labelColor = labelColor,
                    valueColor = valueColor,
                    modifier = Modifier.weight(1f),
                )

                CelestialDataField(
                    label = stringResource(R.string.celestial_distance),
                    value = selectedBody.distance,
                    labelColor = labelColor,
                    valueColor = valueColor,
                    modifier = Modifier.weight(1f),
                )
            }

            // Data Grid Row 2: Equatorial Coordinates (RA & Dec)
            if (selectedBody.raDegrees != null && selectedBody.decDegrees != null) {
                val raFormatted = formatRa(selectedBody.raDegrees)
                val decFormatted = formatDec(selectedBody.decDegrees)

                CelestialDataField(
                    label = stringResource(R.string.celestial_coordinates),
                    value = "$raFormatted  ·  $decFormatted",
                    labelColor = labelColor,
                    valueColor = accentColor,
                    isMonospace = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CelestialDataField(
    label: String,
    value: String,
    labelColor: Color,
    valueColor: Color,
    modifier: Modifier = Modifier,
    isMonospace: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

private fun formatRa(raDegrees: Double): String {
    val totalHours = (raDegrees % 360.0 + 360.0) % 360.0 / 15.0
    val h = totalHours.toInt()
    val remM = (totalHours - h) * 60.0
    val m = remM.toInt()
    val s = ((remM - m) * 60.0).toInt()
    return String.format(Locale.US, "%02dh %02dm %02ds", h, m, s)
}

private fun formatDec(decDegrees: Double): String {
    val sign = if (decDegrees >= 0) "+" else "-"
    val absVal = abs(decDegrees)
    val d = absVal.toInt()
    val remM = (absVal - d) * 60.0
    val m = remM.toInt()
    val s = ((remM - m) * 60.0).toInt()
    return String.format(Locale.US, "%s%02d° %02d' %02d\"", sign, d, m, s)
}
