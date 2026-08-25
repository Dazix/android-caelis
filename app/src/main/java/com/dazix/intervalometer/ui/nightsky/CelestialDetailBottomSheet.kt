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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dazix.intervalometer.R
import com.dazix.intervalometer.ar.paletteForTheme
import com.dazix.intervalometer.domain.nightsky.CelestialBodyDetails
import com.dazix.intervalometer.domain.nightsky.NightVisionTheme
import java.util.Locale
import kotlin.math.abs

/**
 * Material 3 Expressive draggable bottom sheet content displaying rich astronomical details of a selected celestial object.
 * Designed to be embedded in [androidx.compose.material3.BottomSheetScaffold].
 *
 * In PartiallyExpanded (peek) state, displays the Header Row (Name, Type, Divider) perfectly measured via [onHeaderMeasured].
 * In Expanded state, smoothly reveals the complete astrophysics & equatorial coordinate data grid.
 * Fully complies with Night Vision themes (Red, Amber, NVG Green).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CelestialDetailSheetContent(
    selectedBody: CelestialBodyDetails?,
    redVisionMode: Boolean,
    onHeaderMeasured: (Int) -> Unit,
    onFullHeightMeasured: ((Int) -> Unit)? = null,
    nightVisionTheme: NightVisionTheme = NightVisionTheme.AMBER,
    modifier: Modifier = Modifier,
) {
    if (selectedBody == null) {
        Spacer(modifier = Modifier.height(1.dp))
        return
    }

    val palette = paletteForTheme(nightVisionTheme)
    val titleColor = if (redVisionMode) palette.primary else Color.White
    val labelColor = if (redVisionMode) palette.secondary else Color(0xFF8C9BAE)
    val valueColor = if (redVisionMode) palette.primary else Color(0xFFE2E8F0)
    val accentColor = if (redVisionMode) palette.primary else Color(0xFF4DD0E1)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { layoutCoordinates ->
                onFullHeightMeasured?.invoke(layoutCoordinates.size.height)
            },
    ) {
        // HEADER (Visible in the Partially Expanded state)
        // Measured dynamically via onGloballyPositioned (including Drag Handle) for exact peekHeight alignment
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { layoutCoordinates ->
                    onHeaderMeasured(layoutCoordinates.size.height)
                }
        ) {
            // Drag Handle (Inside measured header block)
            BottomSheetDefaults.DragHandle(
                color = if (redVisionMode) palette.primary.copy(alpha = 0.3f) else Color(0xFF718096),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Text(
                text = selectedBody.name,
                color = titleColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
            )

            Text(
                text = selectedBody.subtitle,
                color = labelColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )

            // Divider (Horizontal line aligning with the bottom edge of the peek view)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.8.dp)
                    .background(if (redVisionMode) palette.primary.copy(alpha = 0.25f) else Color(0xFF283248)),
            )
        }

        // REST OF CONTENT (Visible in the Fully Expanded state)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selectedBody.magnitude != null) {
                // STAR LAYOUT:
                // Row 1: Magnitude & Constellation (property1)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    CelestialDataField(
                        label = stringResource(R.string.celestial_magnitude),
                        value = String.format(Locale.US, "V %+.2f", selectedBody.magnitude),
                        labelColor = labelColor,
                        valueColor = accentColor,
                        isMonospace = true,
                        modifier = Modifier.weight(1f),
                    )

                    if (selectedBody.property1 != null) {
                        CelestialDataField(
                            label = selectedBody.property1.first,
                            value = selectedBody.property1.second,
                            labelColor = labelColor,
                            valueColor = valueColor,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                // Row 2: Distance (property2) & Equatorial Coordinates
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (selectedBody.property2 != null) {
                        CelestialDataField(
                            label = selectedBody.property2.first,
                            value = selectedBody.property2.second,
                            labelColor = labelColor,
                            valueColor = valueColor,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (selectedBody.raDegrees != null && selectedBody.decDegrees != null) {
                        val raFormatted = formatRa(selectedBody.raDegrees)
                        val decFormatted = formatDec(selectedBody.decDegrees)

                        CelestialDataField(
                            label = stringResource(R.string.celestial_coordinates),
                            value = "$raFormatted  ·  $decFormatted",
                            labelColor = labelColor,
                            valueColor = accentColor,
                            isMonospace = true,
                            modifier = Modifier.weight(1f),
                        )
                    } else if (selectedBody.property2 == null) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            } else {
                // CONSTELLATION LAYOUT:
                // Row 1: Brightest Star (property1) & Family (property2)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (selectedBody.property1 != null) {
                        CelestialDataField(
                            label = selectedBody.property1.first,
                            value = selectedBody.property1.second,
                            labelColor = labelColor,
                            valueColor = valueColor,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (selectedBody.property2 != null) {
                        CelestialDataField(
                            label = selectedBody.property2.first,
                            value = selectedBody.property2.second,
                            labelColor = labelColor,
                            valueColor = valueColor,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Row 2: Centroid Coordinates
                if (selectedBody.raDegrees != null && selectedBody.decDegrees != null) {
                    val raFormatted = formatRa(selectedBody.raDegrees)
                    val decFormatted = formatDec(selectedBody.decDegrees)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        CelestialDataField(
                            label = stringResource(R.string.celestial_coordinates),
                            value = "$raFormatted  ·  $decFormatted",
                            labelColor = labelColor,
                            valueColor = accentColor,
                            isMonospace = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.weight(1f))
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
