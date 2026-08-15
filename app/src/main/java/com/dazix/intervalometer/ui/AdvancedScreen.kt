package com.dazix.intervalometer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dazix.intervalometer.R
import com.dazix.intervalometer.data.AdvancedSettings
import com.dazix.intervalometer.service.CameraProfile

/**
 * Device- and camera-app-specific extras. Everything is off by default and each row states plainly
 * when the chosen app's profile has no way to do it, instead of hiding the row.
 */
@Composable
fun AdvancedScreen(
    settings: AdvancedSettings,
    profile: CameraProfile,
    targetLabel: String?,
    overlayAllowed: Boolean,
    onChange: (AdvancedSettings) -> Unit,
    onBack: () -> Unit,
) {
    val appName = targetLabel ?: stringResource(R.string.home_target_app_none)
    val noAppReason = stringResource(R.string.advanced_reason_needs_app)
    val unsupportedReason = stringResource(R.string.advanced_reason_needs_pixel, appName)
    val hasApp = targetLabel != null
    fun reason(): String = if (hasApp) unsupportedReason else noAppReason

    ScreenScaffold(title = stringResource(R.string.advanced_title), onBack = onBack) {

        SectionCard(subtitle = stringResource(R.string.advanced_intro)) {
            Text(
                text = stringResource(R.string.advanced_profile, profile.id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The modes come from the profile, never from a literal here: a chip is labelled in the
        // device language ("Night Sight" / "Noční vidění"), so only the profile's own aliases can
        // find it. The UI stores the profile's stable key and shows a localized name for it.
        val modeKeys = LaunchModeOptions.keysFor(profile, hasApp)
        val modeSupported = modeKeys.isNotEmpty()
        val chosenMode = LaunchModeOptions.selected(settings, profile, hasApp)
        SectionCard(
            title = stringResource(R.string.advanced_launch_mode),
            subtitle = stringResource(R.string.advanced_launch_mode_desc),
        ) {
            if (!modeSupported) {
                Text(
                    text = if (hasApp) {
                        stringResource(R.string.advanced_launch_mode_unavailable, appName)
                    } else {
                        noAppReason
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (settings.launchCameraMode != null && chosenMode == null) {
                // Stored from an app whose profile knew that mode; this one does not, so it is off.
                Text(
                    text = stringResource(
                        R.string.advanced_launch_mode_unsupported,
                        modeLabel(settings.launchCameraMode),
                        appName,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ChoiceRow(
                title = stringResource(R.string.advanced_launch_mode_none),
                selected = chosenMode == null,
                enabled = modeSupported,
                onSelect = { onChange(settings.copy(launchCameraMode = null)) },
            )
            modeKeys.forEach { key ->
                ChoiceRow(
                    title = modeLabel(key),
                    selected = chosenMode == key,
                    enabled = modeSupported,
                    onSelect = { onChange(settings.copy(launchCameraMode = key)) },
                )
            }
        }

        SectionCard {
            val adaptiveSupported = hasApp && profile.longExposureBusyPatterns.isNotEmpty()
            SwitchRow(
                title = stringResource(R.string.advanced_adaptive_wait),
                description = stringResource(R.string.advanced_adaptive_wait_desc),
                checked = settings.adaptiveLongExposureWait && adaptiveSupported,
                enabled = adaptiveSupported,
                disabledReason = reason(),
                onCheckedChange = { onChange(settings.copy(adaptiveLongExposureWait = it)) },
            )

            val timerSupported = hasApp &&
                profile.selfTimerOnPatterns.isNotEmpty() &&
                profile.selfTimerOffPatterns.isNotEmpty()
            SwitchRow(
                title = stringResource(R.string.advanced_self_timer),
                description = stringResource(R.string.advanced_self_timer_desc),
                checked = settings.ensureSelfTimerOff && timerSupported,
                enabled = timerSupported,
                disabledReason = reason(),
                onCheckedChange = { onChange(settings.copy(ensureSelfTimerOff = it)) },
            )

            val dismissSupported = hasApp && profile.postCaptureDismissPatterns.isNotEmpty()
            SwitchRow(
                title = stringResource(R.string.advanced_dismiss_post_capture),
                description = stringResource(R.string.advanced_dismiss_post_capture_desc),
                checked = settings.dismissPostCaptureUi && dismissSupported,
                enabled = dismissSupported,
                disabledReason = reason(),
                onCheckedChange = { onChange(settings.copy(dismissPostCaptureUi = it)) },
            )
        }

        // Both of these are carried out by the floating session panel, so they are only available
        // once drawing over other apps is allowed — and both stay off until the user asks for them.
        val overlayReason = stringResource(R.string.advanced_reason_needs_overlay)
        SectionCard(title = stringResource(R.string.advanced_session_screen)) {
            SwitchRow(
                title = stringResource(R.string.advanced_keep_screen_on),
                description = stringResource(R.string.advanced_keep_screen_on_desc),
                checked = settings.keepScreenOn && overlayAllowed,
                enabled = overlayAllowed,
                disabledReason = overlayReason,
                onCheckedChange = { onChange(settings.copy(keepScreenOn = it)) },
            )
            SwitchRow(
                title = stringResource(R.string.advanced_dim_overlay),
                description = stringResource(R.string.advanced_dim_overlay_desc),
                checked = settings.dimOverlay && overlayAllowed,
                enabled = overlayAllowed,
                disabledReason = overlayReason,
                onCheckedChange = { onChange(settings.copy(dimOverlay = it)) },
            )
        }
    }
}

/**
 * Localized names for the mode keys the profiles use. A profile is free to offer a key this table
 * does not know — then the key itself is shown rather than the row disappearing, because a mode the
 * profile can switch to is always worth offering.
 */
private val MODE_LABELS: Map<String, Int> = mapOf(
    CameraProfile.MODE_PHOTO to R.string.advanced_launch_mode_photo,
    CameraProfile.MODE_NIGHT to R.string.advanced_launch_mode_night,
    CameraProfile.MODE_ASTRO to R.string.advanced_launch_mode_astro,
)

@Composable
private fun modeLabel(key: String): String =
    MODE_LABELS[key]?.let { stringResource(it) }
        ?: key.replaceFirstChar { it.uppercase() }
