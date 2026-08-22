package com.dazix.intervalometer.ui.nightsky

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Material 3 Expressive bottom sheet displaying rich astronomical details of a tapped celestial object.
 * Supports peek mode (header + key metrics) and expanded mode (full coordinate & astrophysical data table).
 * Fully complies with Red Vision (Astrophotography Night Mode).
 */
@Composable
fun CelestialDetailBottomSheet(
    selectedBody: CelestialBodyDetails?,
    redVisionMode: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = selectedBody != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        if (selectedBody == null) return@AnimatedVisibility

        var isExpanded by remember { mutableStateOf(false) }

        val containerColor = if (redVisionMode) Color(0xF0180000) else Color(0xF0121622)
        val borderColor = if (redVisionMode) RedPrimary.copy(alpha = 0.8f) else Color(0xFF283248)
        val titleColor = if (redVisionMode) RedPrimary else Color.White
        val labelColor = if (redVisionMode) RedSecondary else Color(0xFF8C9BAE)
        val valueColor = if (redVisionMode) RedPrimary else Color(0xFFE2E8F0)
        val accentColor = if (redVisionMode) RedPrimary else Color(0xFF4DD0E1)
        val badgeContainer = if (redVisionMode) Color(0xFF330000) else Color(0xFF1B2334)

        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = containerColor,
            border = BorderStroke(1.2.dp, borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                // Drag Handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (redVisionMode) RedPrimary.copy(alpha = 0.5f) else Color(0xFF4A5568)),
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Header Row: Object Name, Magnitude/Type Badges, and Dismiss Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
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

                            if (selectedBody.constellation != null && !isExpanded) {
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

                // Expanded Section: Detailed Astrophysics & Celestial Coordinates Table
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Divider
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.8.dp)
                                .background(if (redVisionMode) RedPrimary.copy(alpha = 0.25f) else Color(0xFF283248)),
                        )

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
