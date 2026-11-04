package com.dazix.caelis.ui.nightsky

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dazix.caelis.R
import com.dazix.caelis.ar.scene.paletteForTheme
import com.dazix.caelis.domain.nightsky.CelestialBodyDetails
import com.dazix.caelis.domain.nightsky.CelestialVisibility
import com.dazix.caelis.domain.nightsky.NightVisionTheme
import com.dazix.caelis.domain.nightsky.VisibilityState
import com.dazix.caelis.ui.AppIcons
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
    visibilityState: VisibilityState = VisibilityState.Loading,
    localScheduleDate: LocalDate? = null,
    activeGlobalDate: LocalDate = LocalDate.now(),
    onSetLocalDate: ((LocalDate?) -> Unit)? = null,
    onStepLocalDate: ((Long) -> Unit)? = null,
    redVisionMode: Boolean,
    onHeaderMeasured: (Int) -> Unit,
    onFullHeightMeasured: ((Int) -> Unit)? = null,
    onRouteToTarget: (() -> Unit)? = null,
    onShowDsoLegend: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    nightVisionTheme: NightVisionTheme = NightVisionTheme.AMBER,
    modifier: Modifier = Modifier,
    /** The zone rise, transit and set are shown in: the observer's (a virtual place has its own). */
    zone: ZoneId = ZoneId.systemDefault(),
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = selectedBody.name,
                        color = titleColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )

                    Text(
                        text = selectedBody.subtitle,
                        color = labelColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (onShowDsoLegend != null) {
                        IconButton(
                            onClick = onShowDsoLegend,
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                imageVector = AppIcons.Info,
                                contentDescription = stringResource(R.string.dso_legend_button),
                                tint = accentColor,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    if (onRouteToTarget != null) {
                        IconButton(
                            onClick = onRouteToTarget,
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                imageVector = AppIcons.CenterFocus,
                                contentDescription = stringResource(R.string.celestial_route_to_target),
                                tint = accentColor,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }

                    if (onClose != null) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                imageVector = AppIcons.Clear,
                                contentDescription = stringResource(R.string.celestial_close),
                                tint = labelColor,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

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

                // Row 2: Property3 (if present) & Coordinates
                if (selectedBody.property3 != null || (selectedBody.raDegrees != null && selectedBody.decDegrees != null)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (selectedBody.property3 != null) {
                            CelestialDataField(
                                label = selectedBody.property3.first,
                                value = selectedBody.property3.second,
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
                        } else if (selectedBody.property3 != null) {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        if (selectedBody.property3 == null && selectedBody.raDegrees != null && selectedBody.decDegrees != null) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // Divider before Visibility section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.8.dp)
                    .background(if (redVisionMode) palette.primary.copy(alpha = 0.25f) else Color(0xFF283248)),
            )

            // Visibility Section
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val effectiveDate = localScheduleDate ?: activeGlobalDate
                val isOverridden = localScheduleDate != null
                val currentLocale = LocalConfiguration.current.locales[0]
                val isCzech = currentLocale.language.equals("cs", ignoreCase = true)
                val dateFormatted = remember(effectiveDate, isCzech) {
                    if (isCzech) {
                        val monthStr = when (effectiveDate.monthValue) {
                            1 -> "led"; 2 -> "úno"; 3 -> "bře"; 4 -> "dub"
                            5 -> "kvě"; 6 -> "čer"; 7 -> "čvc"; 8 -> "srp"
                            9 -> "zář"; 10 -> "říj"; 11 -> "lis"; 12 -> "pro"
                            else -> "${effectiveDate.monthValue}"
                        }
                        "${effectiveDate.dayOfMonth}. $monthStr ${effectiveDate.year}"
                    } else {
                        effectiveDate.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.celestial_visibility_title),
                            color = labelColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                        )

                        if (isOverridden && onSetLocalDate != null) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (redVisionMode) palette.primary.copy(alpha = 0.15f) else accentColor.copy(alpha = 0.15f),
                                border = BorderStroke(
                                    0.6.dp,
                                    if (redVisionMode) palette.primary.copy(alpha = 0.4f) else accentColor.copy(alpha = 0.4f),
                                ),
                                onClick = { onSetLocalDate(null) },
                            ) {
                                Text(
                                    text = stringResource(R.string.celestial_visibility_today),
                                    color = accentColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    if (onStepLocalDate != null) {
                        var showDatePicker by remember { mutableStateOf(false) }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            IconButton(
                                onClick = { onStepLocalDate(-1L) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = AppIcons.Back,
                                    contentDescription = stringResource(R.string.celestial_visibility_prev_day),
                                    tint = accentColor,
                                    modifier = Modifier.size(14.dp),
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isOverridden) {
                                    if (redVisionMode) palette.primary.copy(alpha = 0.15f) else accentColor.copy(alpha = 0.12f)
                                } else Color.Transparent,
                                border = if (isOverridden) {
                                    BorderStroke(
                                        0.6.dp,
                                        if (redVisionMode) palette.primary.copy(alpha = 0.4f) else accentColor.copy(alpha = 0.4f),
                                    )
                                } else null,
                                onClick = { showDatePicker = true },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Icon(
                                        imageVector = AppIcons.Calendar,
                                        contentDescription = null,
                                        tint = if (isOverridden) accentColor else labelColor,
                                        modifier = Modifier.size(12.dp),
                                    )
                                    Text(
                                        text = dateFormatted,
                                        color = if (isOverridden) accentColor else valueColor,
                                        fontSize = 11.sp,
                                        fontWeight = if (isOverridden) FontWeight.SemiBold else FontWeight.Medium,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }

                            IconButton(
                                onClick = { onStepLocalDate(1L) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = AppIcons.Forward,
                                    contentDescription = stringResource(R.string.celestial_visibility_next_day),
                                    tint = accentColor,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }

                        if (showDatePicker && onSetLocalDate != null) {
                            val initialMillis = remember(effectiveDate) {
                                effectiveDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                            }
                            val datePickerState = rememberDatePickerState(
                                initialSelectedDateMillis = initialMillis,
                            )
                            val dialogColors = if (redVisionMode) {
                                DatePickerDefaults.colors(
                                    containerColor = Color.Black,
                                    titleContentColor = palette.primary,
                                    headlineContentColor = palette.primary,
                                    weekdayContentColor = palette.secondary,
                                    subheadContentColor = palette.primary,
                                    yearContentColor = palette.secondary,
                                    currentYearContentColor = palette.primary,
                                    selectedYearContentColor = Color.Black,
                                    selectedYearContainerColor = palette.primary,
                                    dayContentColor = palette.primary,
                                    disabledDayContentColor = palette.primary.copy(alpha = 0.2f),
                                    selectedDayContentColor = Color.Black,
                                    selectedDayContainerColor = palette.primary,
                                    todayContentColor = palette.primary,
                                    todayDateBorderColor = palette.primary,
                                    dividerColor = palette.primary.copy(alpha = 0.25f),
                                    navigationContentColor = palette.primary,
                                )
                            } else {
                                DatePickerDefaults.colors(
                                    containerColor = Color(0xFF161B26),
                                    titleContentColor = Color.White,
                                    headlineContentColor = Color(0xFF4DD0E1),
                                    weekdayContentColor = Color(0xFF8C9BAE),
                                    subheadContentColor = Color.White,
                                    yearContentColor = Color(0xFF8C9BAE),
                                    currentYearContentColor = Color(0xFF4DD0E1),
                                    selectedYearContentColor = Color(0xFF0A0E14),
                                    selectedYearContainerColor = Color(0xFF4DD0E1),
                                    dayContentColor = Color(0xFFE2E8F0),
                                    disabledDayContentColor = Color(0xFF4A5568),
                                    selectedDayContentColor = Color(0xFF0A0E14),
                                    selectedDayContainerColor = Color(0xFF4DD0E1),
                                    todayContentColor = Color(0xFF4DD0E1),
                                    todayDateBorderColor = Color(0xFF4DD0E1),
                                    dividerColor = Color(0xFF283248),
                                    navigationContentColor = Color(0xFF4DD0E1),
                                )
                            }
                            DatePickerDialog(
                                onDismissRequest = { showDatePicker = false },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            datePickerState.selectedDateMillis?.let { millis ->
                                                val selectedDate = Instant.ofEpochMilli(millis)
                                                    .atZone(ZoneOffset.UTC)
                                                    .toLocalDate()
                                                onSetLocalDate(selectedDate)
                                            }
                                            showDatePicker = false
                                        }
                                    ) {
                                        Text(
                                            text = stringResource(android.R.string.ok),
                                            color = accentColor,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDatePicker = false }) {
                                        Text(
                                            text = stringResource(android.R.string.cancel),
                                            color = labelColor,
                                        )
                                    }
                                },
                                colors = dialogColors,
                            ) {
                                DatePicker(
                                    state = datePickerState,
                                    colors = dialogColors,
                                )
                            }
                        }
                    }
                }

                when (visibilityState) {
                    is VisibilityState.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = accentColor,
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = stringResource(R.string.celestial_visibility_loading),
                                    color = labelColor,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                    is VisibilityState.Error -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = "—",
                                color = labelColor,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                    is VisibilityState.Loaded -> {
                        when (val vis = visibilityState.visibility) {
                            is CelestialVisibility.RisesAndSets -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    // 1. Rise
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                imageVector = AppIcons.Sunrise,
                                                contentDescription = null,
                                                tint = accentColor,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Text(
                                                text = stringResource(R.string.celestial_visibility_rise),
                                                color = labelColor,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Normal,
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = formatTime(zone, vis.riseInstant),
                                            color = valueColor,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                        if (vis.riseDelayFromAstronomical != null && vis.riseDelayFromAstronomical.toMinutes() > 0) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            ) {
                                                Icon(
                                                    imageVector = AppIcons.Landscape,
                                                    contentDescription = null,
                                                    tint = labelColor,
                                                    modifier = Modifier.size(10.dp),
                                                )
                                                Text(
                                                    text = stringResource(
                                                        R.string.celestial_visibility_terrain_delay_format,
                                                        vis.riseDelayFromAstronomical.toMinutes(),
                                                    ),
                                                    color = labelColor,
                                                    fontSize = 10.sp,
                                                )
                                            }
                                        }
                                    }

                                    // 2. Culmination / Transit
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                imageVector = AppIcons.Schedule,
                                                contentDescription = null,
                                                tint = accentColor,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Text(
                                                text = stringResource(R.string.celestial_visibility_transit),
                                                color = labelColor,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Normal,
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = formatTime(zone, vis.transitInstant),
                                            color = valueColor,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = String.format(Locale.US, "%+.0f°", vis.maxElevationDegrees),
                                            color = accentColor,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Normal,
                                        )
                                    }

                                    // 3. Set
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                imageVector = AppIcons.Sunset,
                                                contentDescription = null,
                                                tint = accentColor,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Text(
                                                text = stringResource(R.string.celestial_visibility_set),
                                                color = labelColor,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Normal,
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = formatTime(zone, vis.setInstant),
                                            color = valueColor,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                        if (vis.setDelayFromAstronomical != null && vis.setDelayFromAstronomical.toMinutes() > 0) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            ) {
                                                Icon(
                                                    imageVector = AppIcons.Landscape,
                                                    contentDescription = null,
                                                    tint = labelColor,
                                                    modifier = Modifier.size(10.dp),
                                                )
                                                Text(
                                                    text = stringResource(
                                                        R.string.celestial_visibility_terrain_delay_format,
                                                        vis.setDelayFromAstronomical.toMinutes(),
                                                    ),
                                                    color = labelColor,
                                                    fontSize = 10.sp,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            is CelestialVisibility.AlwaysAboveHorizon -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            imageVector = AppIcons.Star,
                                            contentDescription = null,
                                            tint = accentColor,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = stringResource(R.string.celestial_visibility_circumpolar),
                                            color = valueColor,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                    Text(
                                        text = "${stringResource(R.string.celestial_visibility_transit)}: ${formatTime(zone, vis.transitInstant)} (${String.format(Locale.US, "%+.0f°", vis.maxElevationDegrees)})",
                                        color = labelColor,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                            is CelestialVisibility.AlwaysBelowHorizon -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.celestial_visibility_never_rises),
                                        color = labelColor,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = "${stringResource(R.string.celestial_visibility_transit)}: ${formatTime(zone, vis.transitInstant)} (${String.format(Locale.US, "%+.0f°", vis.maxElevationDegrees)})",
                                        color = labelColor.copy(alpha = 0.8f),
                                        fontSize = 11.sp,
                                    )
                                }
                            }
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

private fun formatTime(zone: ZoneId, instant: Instant): String {
    val localTime = instant.atZone(zone).toLocalTime()
    return String.format(Locale.US, "%02d:%02d", localTime.hour, localTime.minute)
}
