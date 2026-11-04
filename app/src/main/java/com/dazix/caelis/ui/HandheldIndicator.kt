package com.dazix.caelis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dazix.caelis.R
import com.dazix.caelis.domain.stability.HandheldPolicy
import com.dazix.caelis.domain.stability.HandheldState
import com.dazix.caelis.monitor.MotionMonitor

/**
 * The gyroscope's verdict while the home screen is in front and [watch] holds, or
 * [HandheldState.UNKNOWN] otherwise. Listens only while the screen is resumed: a sensor left
 * registered behind a paused activity costs battery for a chip nobody sees.
 */
@Composable
fun rememberHandheldState(watch: Boolean): State<HandheldState> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val monitor = remember(context) { MotionMonitor(context) }
    val state = remember { mutableStateOf(HandheldState.UNKNOWN) }

    DisposableEffect(watch, lifecycleOwner, monitor) {
        fun startIfResumed() {
            if (watch && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                monitor.start { state.value = it }
            }
        }

        fun stop() {
            monitor.stop()
            state.value = HandheldState.UNKNOWN
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> startIfResumed()
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        startIfResumed()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stop()
        }
    }
    return state
}

/** Whether this phone has the gyroscope the check needs; without one it never answers. */
@Composable
fun rememberHasGyroscope(): Boolean {
    val context = LocalContext.current
    return remember(context) { MotionMonitor(context).isAvailable }
}

/**
 * "Steady" or "Phone in hand" under the session card before a time-lapse starts. While the first
 * window fills (on opening the screen and on every return to it) the line says it is checking when
 * [holdLine] is set, so the verdict replaces it in place instead of pushing the screen down three
 * seconds in (see [HandheldPolicy.holdStatusLine]). Without [holdLine] an unknown answer shows nothing.
 * Icon and text both carry the state, never the colour alone.
 */
@Composable
fun HandheldStatusRow(state: HandheldState, holdLine: Boolean, modifier: Modifier = Modifier) {
    val handheld = when (state) {
        HandheldState.HANDHELD -> true
        HandheldState.STEADY -> false
        HandheldState.UNKNOWN -> if (holdLine) null else return
    }
    val text = stringResource(
        when (handheld) {
            true -> R.string.home_handheld_detected
            false -> R.string.home_handheld_steady
            null -> R.string.home_handheld_checking
        },
    )
    val color = if (handheld == true) MaterialTheme.colorScheme.error else LocalContentColor.current
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = text
                // Changes while the user is looking elsewhere (mounting the phone), so say it aloud.
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = when (handheld) {
                true -> AppIcons.Sensors
                false -> AppIcons.Check
                null -> AppIcons.Pending
            },
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

/**
 * Wraps [onStart]: when [HandheldPolicy.confirmStart] says the phone was in the hand over the last
 * window, Start first asks whether to go ahead anyway. The window is the one the home screen has been
 * running all along, so the answer is immediate — nothing waits three seconds after the press.
 */
@Composable
fun rememberHandheldStartGuard(
    state: HandheldState,
    timelapse: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
): () -> Unit {
    var asking by rememberSaveable { mutableStateOf(false) }
    val currentOnStart by rememberUpdatedState(onStart)

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            icon = { Icon(AppIcons.Sensors, contentDescription = null) },
            title = { Text(stringResource(R.string.home_handheld_dialog_title)) },
            text = {
                Text(
                    text = stringResource(R.string.home_handheld_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        asking = false
                        currentOnStart()
                    },
                ) { Text(stringResource(R.string.home_handheld_dialog_start_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) {
                    Text(stringResource(R.string.home_handheld_dialog_cancel))
                }
            },
        )
    }

    return {
        if (HandheldPolicy.confirmStart(state, timelapse, enabled)) asking = true else currentOnStart()
    }
}
