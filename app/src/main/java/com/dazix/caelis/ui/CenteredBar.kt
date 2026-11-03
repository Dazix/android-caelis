package com.dazix.caelis.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints

/**
 * A bar with [start] and [end] controls and [center] content centred on the whole bar, not on the
 * space left between the controls. With one button on the left and two on the right, a weighted
 * middle sat visibly left of the pill's centre in both AR top bars.
 *
 * The centre gets the room between the controls at most; when it is wider than the symmetric gap it
 * is moved towards the narrower side just enough to stay clear of the controls, rather than
 * overlapping them.
 */
@Composable
fun CenteredBar(
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    center: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(start, center, end),
        modifier = modifier,
    ) { (startMeasurables, centerMeasurables, endMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val startPlaceables = startMeasurables.map { it.measure(loose) }
        val endPlaceables = endMeasurables.map { it.measure(loose) }
        val startWidth = startPlaceables.maxOfOrNull { it.width } ?: 0
        val endWidth = endPlaceables.maxOfOrNull { it.width } ?: 0
        val width = constraints.maxWidth
        val centerMax = (width - startWidth - endWidth).coerceAtLeast(0)
        val centerPlaceables = centerMeasurables.map {
            it.measure(Constraints(maxWidth = centerMax, maxHeight = loose.maxHeight))
        }
        val centerWidth = centerPlaceables.maxOfOrNull { it.width } ?: 0
        val height = (startPlaceables + centerPlaceables + endPlaceables)
            .maxOfOrNull { it.height }
            ?.coerceIn(constraints.minHeight, constraints.maxHeight)
            ?: constraints.minHeight
        layout(width, height) {
            startPlaceables.forEach { it.place(0, (height - it.height) / 2) }
            endPlaceables.forEach { it.place(width - it.width, (height - it.height) / 2) }
            val centeredX = (width - centerWidth) / 2
            val x = centeredX.coerceIn(startWidth, (width - endWidth - centerWidth).coerceAtLeast(startWidth))
            centerPlaceables.forEach { it.place(x, (height - it.height) / 2) }
        }
    }
}
