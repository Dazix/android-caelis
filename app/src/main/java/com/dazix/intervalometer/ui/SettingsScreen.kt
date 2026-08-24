package com.dazix.intervalometer.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dazix.intervalometer.R
import com.dazix.intervalometer.domain.nightsky.NightVisionActivationMode
import com.dazix.intervalometer.domain.nightsky.NightVisionTheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    theme: NightVisionTheme,
    activationMode: NightVisionActivationMode,
    startTime: LocalTime,
    endTime: LocalTime,
    onThemeChange: (NightVisionTheme) -> Unit,
    onActivationModeChange: (NightVisionActivationMode) -> Unit,
    onStartTimeChange: (LocalTime) -> Unit,
    onEndTimeChange: (LocalTime) -> Unit,
    onBack: () -> Unit,
) {
    var showStartTimeDialog by remember { mutableStateOf(false) }
    var showEndTimeDialog by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.settings_subtitle),
        onBack = onBack,
    ) {
        // ---- Section 1: Night Vision Color Theme ----
        SectionCard(
            title = stringResource(R.string.settings_theme_title),
            subtitle = stringResource(R.string.settings_theme_subtitle),
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                NightVisionTheme.entries.forEachIndexed { index, item ->
                    val isSelected = item == theme
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

                    SegmentedButton(
                        selected = isSelected,
                        onClick = { onThemeChange(item) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = NightVisionTheme.entries.size),
                        icon = {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(themeColor, CircleShape),
                            )
                        },
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
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
