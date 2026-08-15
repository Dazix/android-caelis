package com.dazix.intervalometer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The app's own icon set, drawn in code.
 *
 * `androidx.compose.material:material-icons-*` is deliberately not a dependency: the generic glyph
 * set has nothing for "interval ramp" or "shutter", and the handful of shapes this app needs are
 * plain geometry. Every icon is authored on the same 24dp grid with the same 2.2dp stroke, so the set
 * reads as one family rather than as glyphs borrowed from three places.
 *
 * All of them are monochrome and are tinted by `Icon`, which is what keeps them correct in both
 * colour schemes. None of them ever carries meaning on its own: every icon-only control in the app
 * has a content description, and every icon next to a label is passed `contentDescription = null` so
 * a screen reader reads the label once instead of twice.
 */
object AppIcons {

    private const val STROKE = 2.2f

    /** Shutter: what the app actually presses. Stands for the session as a whole. */
    val Session: ImageVector by lazy {
        icon("Session") {
            strokePath {
                circle(centerX = 12f, centerY = 12f, radius = 8.4f)
            }
            fillPath {
                circle(centerX = 12f, centerY = 12f, radius = 3.6f)
            }
        }
    }

    /** Two sliders with their thumbs: the session's numbers. */
    val Setup: ImageVector by lazy {
        icon("Setup") {
            strokePath {
                moveTo(3.4f, 8.5f)
                lineTo(20.6f, 8.5f)
                moveTo(3.4f, 15.5f)
                lineTo(20.6f, 15.5f)
            }
            fillPath {
                circle(centerX = 9f, centerY = 8.5f, radius = 3f)
                circle(centerX = 16f, centerY = 15.5f, radius = 3f)
            }
        }
    }

    /** A rising interval: the ramp curve, with the two intervals it runs between marked. */
    val Ramp: ImageVector by lazy {
        icon("Ramp") {
            strokePath {
                moveTo(3.6f, 18.4f)
                lineTo(9.4f, 14.2f)
                lineTo(15f, 9.2f)
                lineTo(20.4f, 5.6f)
            }
            fillPath {
                circle(centerX = 3.6f, centerY = 18.4f, radius = 2.1f)
                circle(centerX = 20.4f, centerY = 5.6f, radius = 2.1f)
            }
        }
    }

    /** The universal "and the rest": the optional per-device extras. */
    val Advanced: ImageVector by lazy {
        icon("Advanced") {
            fillPath {
                circle(centerX = 5.2f, centerY = 12f, radius = 2f)
                circle(centerX = 12f, centerY = 12f, radius = 2f)
                circle(centerX = 18.8f, centerY = 12f, radius = 2f)
            }
        }
    }

    /** A shield: everything the system has to allow before the app can tap. */
    val Permissions: ImageVector by lazy {
        icon("Permissions") {
            fillPath {
                moveTo(12f, 2.2f)
                lineTo(4f, 5.4f)
                lineTo(4f, 11.6f)
                curveTo(4f, 16.6f, 8f, 20.8f, 12f, 22f)
                curveTo(16f, 20.8f, 20f, 16.6f, 20f, 11.6f)
                lineTo(20f, 5.4f)
                close()
            }
        }
    }

    /** Start. */
    val Play: ImageVector by lazy {
        icon("Play") {
            fillPath {
                moveTo(8f, 5.2f)
                lineTo(19f, 12f)
                lineTo(8f, 18.8f)
                close()
            }
        }
    }

    /** Stop: a rounded square, so it is not mistaken for the shutter. */
    val Stop: ImageVector by lazy {
        icon("Stop") {
            fillPath {
                moveTo(8.4f, 6.6f)
                lineTo(15.6f, 6.6f)
                arcTo(1.8f, 1.8f, 0f, false, true, 17.4f, 8.4f)
                lineTo(17.4f, 15.6f)
                arcTo(1.8f, 1.8f, 0f, false, true, 15.6f, 17.4f)
                lineTo(8.4f, 17.4f)
                arcTo(1.8f, 1.8f, 0f, false, true, 6.6f, 15.6f)
                lineTo(6.6f, 8.4f)
                arcTo(1.8f, 1.8f, 0f, false, true, 8.4f, 6.6f)
                close()
            }
        }
    }

    /** Pause. */
    val Pause: ImageVector by lazy {
        icon("Pause") {
            fillPath {
                roundedBar(left = 7.4f, right = 10.6f)
                roundedBar(left = 13.4f, right = 16.6f)
            }
        }
    }

    /** A crosshair: designating where on screen the shutter is. */
    val Calibrate: ImageVector by lazy {
        icon("Calibrate") {
            strokePath {
                circle(centerX = 12f, centerY = 12f, radius = 6f)
                moveTo(12f, 1.8f)
                lineTo(12f, 5f)
                moveTo(12f, 19f)
                lineTo(12f, 22.2f)
                moveTo(1.8f, 12f)
                lineTo(5f, 12f)
                moveTo(19f, 12f)
                lineTo(22.2f, 12f)
            }
            fillPath {
                circle(centerX = 12f, centerY = 12f, radius = 1.8f)
            }
        }
    }

    /** Up one level. The app has exactly one level, so this is always "back to the session". */
    val Back: ImageVector by lazy {
        icon("Back") {
            strokePath {
                moveTo(14.6f, 4.8f)
                lineTo(7.4f, 12f)
                lineTo(14.6f, 19.2f)
            }
        }
    }

    /** Granted / verified. Always paired with words, never the only signal. */
    val Check: ImageVector by lazy {
        icon("Check") {
            strokePath {
                moveTo(4.8f, 12.6f)
                lineTo(9.6f, 17.4f)
                lineTo(19.2f, 6.6f)
            }
        }
    }

    /** Still missing. Always paired with words, never the only signal. */
    val Pending: ImageVector by lazy {
        icon("Pending") {
            strokePath {
                circle(centerX = 12f, centerY = 12f, radius = 8.4f)
                moveTo(12f, 7.4f)
                lineTo(12f, 13f)
            }
            fillPath {
                circle(centerX = 12f, centerY = 16.4f, radius = 1.3f)
            }
        }
    }

    /** Empties the search field. */
    val Clear: ImageVector by lazy {
        icon("Clear") {
            strokePath {
                moveTo(7f, 7f)
                lineTo(17f, 17f)
                moveTo(17f, 7f)
                lineTo(7f, 17f)
            }
        }
    }

    private fun icon(name: String, paths: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(paths).build()

    private fun ImageVector.Builder.fillPath(
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ) = path(fill = SolidColor(Color.Black), pathBuilder = block)

    private fun ImageVector.Builder.strokePath(
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ) = path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = STROKE,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )

    /** A full circle as two half arcs, which is the only exact way to write one in path data. */
    private fun androidx.compose.ui.graphics.vector.PathBuilder.circle(
        centerX: Float,
        centerY: Float,
        radius: Float,
    ) {
        moveTo(centerX, centerY - radius)
        arcTo(radius, radius, 0f, true, true, centerX, centerY + radius)
        arcTo(radius, radius, 0f, true, true, centerX, centerY - radius)
        close()
    }

    /** One of the two pause bars: full-height, rounded ends. */
    private fun androidx.compose.ui.graphics.vector.PathBuilder.roundedBar(
        left: Float,
        right: Float,
    ) {
        val radius = (right - left) / 2f
        moveTo(left, 7.6f)
        arcTo(radius, radius, 0f, true, true, right, 7.6f)
        lineTo(right, 16.4f)
        arcTo(radius, radius, 0f, true, true, left, 16.4f)
        close()
    }
}
