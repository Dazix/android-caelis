package com.dazix.caelis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R

/**
 * The permission checklist. Every row reads live platform state — the caller re-reads it on resume,
 * because all of these are granted in Settings and then the user comes back.
 */
@Composable
fun OnboardingScreen(
    permissions: PermissionState,
    onOpenAppInfo: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    ScreenScaffold(
        title = stringResource(R.string.onboarding_title),
        onBack = onBack,
        trailing = {
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.action_recheck)) }
        },
    ) {
        SectionCard(
            subtitle = stringResource(R.string.onboarding_intro),
            tone = if (permissions.allGranted) CardTone.ACCENT else CardTone.NEUTRAL,
        ) {
            if (permissions.allGranted) {
                StatusChip(text = stringResource(R.string.onboarding_all_set), granted = true)
            }
        }

        PermissionRow(
            title = stringResource(R.string.onboarding_restricted_title),
            description = if (permissions.installedFromOutsideStore) {
                stringResource(R.string.onboarding_restricted_desc)
            } else {
                stringResource(R.string.onboarding_restricted_not_needed)
            },
            // Android never tells an app whether the restriction is still in force; the accessibility
            // switch flipping on is the proof, so that is what this row reports.
            granted = permissions.accessibilityEnabled || !permissions.installedFromOutsideStore,
            optional = !permissions.installedFromOutsideStore,
            actionLabel = stringResource(R.string.onboarding_restricted_action),
            onAction = onOpenAppInfo,
        )

        PermissionRow(
            title = stringResource(R.string.onboarding_accessibility_title),
            description = stringResource(R.string.onboarding_accessibility_desc),
            granted = permissions.accessibilityEnabled,
            actionLabel = stringResource(R.string.onboarding_accessibility_action),
            onAction = onOpenAccessibilitySettings,
        )

        PermissionRow(
            title = stringResource(R.string.onboarding_overlay_title),
            description = stringResource(R.string.onboarding_overlay_desc),
            granted = permissions.canDrawOverlays,
            actionLabel = stringResource(R.string.onboarding_overlay_action),
            onAction = onOpenOverlaySettings,
        )

        PermissionRow(
            title = stringResource(R.string.onboarding_notifications_title),
            description = stringResource(R.string.onboarding_notifications_desc),
            granted = permissions.notificationsGranted,
            actionLabel = stringResource(R.string.onboarding_notifications_action),
            onAction = onRequestNotifications,
        )

        PermissionRow(
            title = stringResource(R.string.onboarding_battery_title),
            description = stringResource(R.string.onboarding_battery_desc),
            granted = permissions.ignoringBatteryOptimizations,
            actionLabel = stringResource(R.string.onboarding_battery_action),
            onAction = onRequestBatteryExemption,
        )

        PermissionRow(
            title = stringResource(R.string.onboarding_exact_alarm_title),
            description = stringResource(R.string.onboarding_exact_alarm_desc),
            granted = permissions.exactAlarmsAllowed,
            actionLabel = stringResource(R.string.onboarding_exact_alarm_action),
            onAction = onOpenExactAlarmSettings,
        )

        SectionCard(title = stringResource(R.string.onboarding_media_title)) {
            Text(
                text = stringResource(R.string.onboarding_media_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.75f),
            )
            StatusLabel(granted = permissions.readMediaImagesGranted, optional = true)
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    optional: Boolean = false,
) {
    SectionCard(title = title) {
        // Follows the card's own content colour: the intro card above switches to an accent tone when
        // everything is granted, and a named role would not have followed it.
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.copy(alpha = 0.75f),
        )
        // Wraps rather than squeezing the action off the edge once the labels grow with the font
        // scale — several of these read "Open accessibility settings" in two languages.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            StatusLabel(granted = granted, optional = optional)
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Words first, then an icon, then a colour: three signals for the same state, never one alone. */
@Composable
private fun StatusLabel(granted: Boolean, optional: Boolean) {
    StatusChip(
        text = when {
            granted -> stringResource(R.string.onboarding_granted)
            optional -> stringResource(R.string.onboarding_optional)
            else -> stringResource(R.string.onboarding_missing)
        },
        granted = granted,
        muted = optional,
    )
}
