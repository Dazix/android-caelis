package com.dazix.intervalometer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dazix.intervalometer.R
import com.dazix.intervalometer.domain.IntervalSchedule
import com.dazix.intervalometer.domain.RampCurve
import com.dazix.intervalometer.domain.RampDomain
import com.dazix.intervalometer.domain.SessionConfig

@Composable
fun RampScreen(
    state: SetupUiState,
    floorMs: Long,
    onChange: (SetupUiState) -> Unit,
    onBack: () -> Unit,
) {
    ScreenScaffold(title = stringResource(R.string.ramp_title), onBack = onBack) {

        SectionCard {
            SwitchRow(
                title = stringResource(R.string.ramp_enable),
                description = stringResource(R.string.ramp_enable_desc),
                checked = state.rampEnabled,
                onCheckedChange = { onChange(state.copy(rampEnabled = it)) },
            )
            if (!state.rampEnabled) {
                // Secondary text inside a card follows the card's own content colour rather than a
                // named role, so a card tone or a dynamic scheme cannot leave it low-contrast.
                Text(
                    text = stringResource(R.string.ramp_disabled_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                )
            }
        }

        SectionCard(title = stringResource(R.string.ramp_end_interval)) {
            Text(
                text = stringResource(
                    R.string.ramp_start_interval,
                    durationText(state.intervalMs),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.75f),
            )
            DurationRow(
                label = stringResource(R.string.ramp_end_interval),
                value = state.rampEndValue,
                unit = state.rampEndUnit,
                enabled = state.rampEnabled,
                onValueChange = { onChange(state.copy(rampEndValue = it)) },
                onUnitChange = { onChange(state.copy(rampEndUnit = it)) },
            )
        }

        SectionCard(title = stringResource(R.string.ramp_curve)) {
            SegmentedChoice(
                options = listOf(RampCurve.LINEAR, RampCurve.EXPONENTIAL),
                selected = state.rampCurve,
                onSelect = { curve -> onChange(state.copy(rampCurve = curve)) },
                enabled = state.rampEnabled,
            ) { curve -> stringResource(curveLabel(curve)) }
            Text(
                text = stringResource(curveDescription(state.rampCurve)),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.75f),
            )
        }

        // Both inputs stay on screen with the same enable rules they always had: the one that is off
        // still shows the value it would use if the choice above it were the other one.
        SectionCard(title = stringResource(R.string.ramp_domain)) {
            SegmentedChoice(
                options = listOf(RampDomain.SHOT_INDEX, RampDomain.ELAPSED_TIME),
                selected = state.rampDomain,
                onSelect = { domain -> onChange(state.copy(rampDomain = domain)) },
                enabled = state.rampEnabled,
            ) { domain -> stringResource(domainLabel(domain)) }
            NumberField(
                label = stringResource(R.string.ramp_over_shots),
                value = state.rampOverShots,
                onValueChange = { onChange(state.copy(rampOverShots = it)) },
                suffix = stringResource(R.string.unit_shots),
                enabled = state.rampEnabled && state.rampDomain == RampDomain.SHOT_INDEX,
                modifier = Modifier.fillMaxWidth(),
            )
            DurationRow(
                label = stringResource(R.string.ramp_over_duration),
                value = state.rampOverDurationValue,
                unit = state.rampOverDurationUnit,
                enabled = state.rampEnabled && state.rampDomain == RampDomain.ELAPSED_TIME,
                onValueChange = { onChange(state.copy(rampOverDurationValue = it)) },
                onUnitChange = { onChange(state.copy(rampOverDurationUnit = it)) },
            )
        }

        SectionCard(
            title = stringResource(R.string.ramp_preview),
            subtitle = stringResource(R.string.ramp_preview_desc),
        ) {
            val preview = if (state.rampEnabled) rampPreview(state.toConfig(floorMs)) else null
            if (preview == null || preview.rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.ramp_preview_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val truncated = preview.rows.size > PREVIEW_EDGE * 2
                    val head = preview.rows.take(PREVIEW_EDGE)
                    val tail = if (truncated) {
                        preview.rows.takeLast(PREVIEW_EDGE)
                    } else {
                        preview.rows.drop(PREVIEW_EDGE)
                    }
                    head.forEach { PreviewRow(it.first, it.second) }
                    if (truncated) {
                        Text(
                            text = stringResource(R.string.ramp_preview_more),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    tail.forEach { PreviewRow(it.first, it.second) }
                }
                HorizontalDivider()
                Text(
                    // A plural, not a format string: Czech needs four forms of "snímek" and the
                    // preview really does show totals of 1, of 2-4 and of 5 upwards.
                    text = pluralStringResource(
                        R.plurals.ramp_preview_total,
                        preview.shots,
                        preview.shots,
                        durationText(preview.totalMs),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun PreviewRow(index: Int, intervalMs: Long) {
    Text(
        text = stringResource(R.string.ramp_preview_row, index + 1, durationText(intervalMs)),
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun curveLabel(curve: RampCurve): Int = when (curve) {
    RampCurve.LINEAR -> R.string.ramp_curve_linear
    RampCurve.EXPONENTIAL -> R.string.ramp_curve_exponential
}

private fun curveDescription(curve: RampCurve): Int = when (curve) {
    RampCurve.LINEAR -> R.string.ramp_curve_linear_desc
    RampCurve.EXPONENTIAL -> R.string.ramp_curve_exponential_desc
}

private fun domainLabel(domain: RampDomain): Int = when (domain) {
    RampDomain.SHOT_INDEX -> R.string.ramp_domain_shots
    RampDomain.ELAPSED_TIME -> R.string.ramp_domain_duration
}

private const val PREVIEW_EDGE = 4
private const val PREVIEW_LIMIT = 2_000

internal class RampPreviewData(
    val rows: List<Pair<Int, Long>>,
    val totalMs: Long,
    val shots: Int,
)

/**
 * Runs the real [IntervalSchedule] over the configured domain, so the list the user reads is the
 * same arithmetic the session will use rather than a second implementation of the curve.
 */
internal fun rampPreview(config: SessionConfig): RampPreviewData? {
    val ramp = config.ramp ?: return null
    val schedule = IntervalSchedule(config)
    val cap = when (ramp.domain) {
        RampDomain.SHOT_INDEX -> ramp.overShots?.takeIf { it > 0 } ?: return null
        RampDomain.ELAPSED_TIME -> {
            if ((ramp.overDurationMs ?: 0L) <= 0L) return null
            PREVIEW_LIMIT
        }
    }
    val limit = minOf(cap, config.stop.maxShots?.takeIf { it > 0 } ?: cap, PREVIEW_LIMIT)
    val rows = ArrayList<Pair<Int, Long>>()
    var elapsed = 0L
    var index = 0
    while (index <= limit) {
        val interval = schedule.intervalForShot(index, elapsed)
        rows.add(index to interval)
        elapsed += interval
        index++
        if (ramp.domain == RampDomain.ELAPSED_TIME && elapsed >= (ramp.overDurationMs ?: 0L)) break
    }
    return RampPreviewData(rows = rows, totalMs = elapsed, shots = rows.size)
}
