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

    /** Forward: next day arrow. */
    val Forward: ImageVector by lazy {
        icon("Forward") {
            strokePath {
                moveTo(9.4f, 4.8f)
                lineTo(16.6f, 12f)
                lineTo(9.4f, 19.2f)
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

    /** Sun: central disk with 8 radial rays. Stands for Sun AR and solar trajectory. */
    val Sun: ImageVector by lazy {
        icon("Sun") {
            strokePath {
                circle(centerX = 12f, centerY = 12f, radius = 4.2f)
                // 4 cardinal rays
                moveTo(12f, 2.4f); lineTo(12f, 5.2f)
                moveTo(12f, 18.8f); lineTo(12f, 21.6f)
                moveTo(2.4f, 12f); lineTo(5.2f, 12f)
                moveTo(18.8f, 12f); lineTo(21.6f, 12f)
                // 4 diagonal rays
                moveTo(5.2f, 5.2f); lineTo(7.2f, 7.2f)
                moveTo(16.8f, 16.8f); lineTo(18.8f, 18.8f)
                moveTo(5.2f, 18.8f); lineTo(7.2f, 16.8f)
                moveTo(16.8f, 7.2f); lineTo(18.8f, 5.2f)
            }
        }
    }

    /** Sun and Moon: radiant sun combined with a crescent moon. Stands for Sun & Moon AR ephemeris. */
    val SunAndMoon: ImageVector by lazy {
        icon("SunAndMoon") {
            strokePath {
                // Sun disk arc on left
                moveTo(11.5f, 7.8f)
                arcTo(4.2f, 4.2f, 0f, false, false, 11.5f, 16.2f)

                // Sun rays (left, top-left, bottom-left, top, bottom)
                moveTo(2.4f, 12f); lineTo(5.2f, 12f)
                moveTo(4.8f, 5.5f); lineTo(7.0f, 7.5f)
                moveTo(4.8f, 18.5f); lineTo(7.0f, 16.5f)
                moveTo(10.5f, 2.4f); lineTo(10.5f, 5.0f)
                moveTo(10.5f, 19.0f); lineTo(10.5f, 21.6f)

                // Crescent Moon on right
                moveTo(13.5f, 4.8f)
                arcTo(7.4f, 7.4f, 0f, false, true, 13.5f, 19.2f)
                arcTo(5.2f, 5.2f, 0f, false, false, 13.5f, 4.8f)
            }
        }
    }

    /** Sunrise: rising sun over the horizon line with an upward arrow. */
    val Sunrise: ImageVector by lazy {
        icon("Sunrise") {
            strokePath {
                // Horizon
                moveTo(2.4f, 17.5f)
                lineTo(21.6f, 17.5f)
                // Rising sun arc
                moveTo(7.2f, 17.5f)
                arcTo(4.8f, 4.8f, 0f, false, true, 16.8f, 17.5f)
                // Up arrow
                moveTo(12f, 10.5f)
                lineTo(12f, 3.2f)
                moveTo(9f, 6.2f)
                lineTo(12f, 3.2f)
                lineTo(15f, 6.2f)
            }
        }
    }

    /** Sunset: setting sun over the horizon line with a downward arrow. */
    val Sunset: ImageVector by lazy {
        icon("Sunset") {
            strokePath {
                // Horizon
                moveTo(2.4f, 17.5f)
                lineTo(21.6f, 17.5f)
                // Setting sun arc
                moveTo(7.2f, 17.5f)
                arcTo(4.8f, 4.8f, 0f, false, true, 16.8f, 17.5f)
                // Down arrow
                moveTo(12f, 3.2f)
                lineTo(12f, 10.5f)
                moveTo(9f, 7.5f)
                lineTo(12f, 10.5f)
                lineTo(15f, 7.5f)
            }
        }
    }

    /** Compass: circular dial with orientation needle. */
    val Compass: ImageVector by lazy {
        icon("Compass") {
            strokePath {
                circle(centerX = 12f, centerY = 12f, radius = 8.6f)
                moveTo(12f, 6.2f)
                lineTo(14.8f, 12f)
                lineTo(12f, 17.8f)
                lineTo(9.2f, 12f)
                close()
            }
            fillPath {
                moveTo(12f, 6.2f)
                lineTo(14.8f, 12f)
                lineTo(12f, 12f)
                close()
            }
        }
    }

    /** Calendar: date selector icon. */
    val Calendar: ImageVector by lazy {
        icon("Calendar") {
            strokePath {
                moveTo(5.2f, 4.6f)
                lineTo(18.8f, 4.6f)
                arcTo(2f, 2f, 0f, false, true, 20.8f, 6.6f)
                lineTo(20.8f, 19.4f)
                arcTo(2f, 2f, 0f, false, true, 18.8f, 21.4f)
                lineTo(5.2f, 21.4f)
                arcTo(2f, 2f, 0f, false, true, 3.2f, 19.4f)
                lineTo(3.2f, 6.6f)
                arcTo(2f, 2f, 0f, false, true, 5.2f, 4.6f)
                close()
                moveTo(3.2f, 9.6f)
                lineTo(20.8f, 9.6f)
                moveTo(7.8f, 2.4f); lineTo(7.8f, 5.6f)
                moveTo(16.2f, 2.4f); lineTo(16.2f, 5.6f)
            }
        }
    }

    /** ScreenAwake: display stay awake / keep screen on icon. */
    val ScreenAwake: ImageVector by lazy {
        icon("ScreenAwake") {
            strokePath {
                moveTo(6.5f, 3.2f)
                lineTo(17.5f, 3.2f)
                arcTo(2f, 2f, 0f, false, true, 19.5f, 5.2f)
                lineTo(19.5f, 18.8f)
                arcTo(2f, 2f, 0f, false, true, 17.5f, 20.8f)
                lineTo(6.5f, 20.8f)
                arcTo(2f, 2f, 0f, false, true, 4.5f, 18.8f)
                lineTo(4.5f, 5.2f)
                arcTo(2f, 2f, 0f, false, true, 6.5f, 3.2f)
                close()
                moveTo(10.5f, 18.2f)
                lineTo(13.5f, 18.2f)
                // Center glow / sun dot
                moveTo(12f, 10f)
                lineTo(12f, 14f)
                moveTo(10f, 12f)
                lineTo(14f, 12f)
            }
        }
    }

    /** RedVision: night vision / astrophotography eye mode icon. */
    val RedVision: ImageVector by lazy {
        icon("RedVision") {
            strokePath {
                moveTo(2.5f, 12f)
                curveTo(4.5f, 7.5f, 8f, 5f, 12f, 5f)
                curveTo(16f, 5f, 19.5f, 7.5f, 21.5f, 12f)
                curveTo(19.5f, 16.5f, 16f, 19f, 12f, 19f)
                curveTo(8f, 19f, 4.5f, 16.5f, 2.5f, 12f)
                close()
                circle(centerX = 12f, centerY = 12f, radius = 3.2f)
            }
        }
    }

    /** ResetCalibration: undo / refresh calibration rotation icon. */
    val ResetCalibration: ImageVector by lazy {
        icon("ResetCalibration") {
            strokePath {
                moveTo(3.5f, 12f)
                arcTo(8.5f, 8.5f, 0f, true, true, 6f, 18f)
                moveTo(3.5f, 7f)
                lineTo(3.5f, 12f)
                lineTo(8.5f, 12f)
            }
        }
    }

    /** NightSky: starry night / constellation icon for Night Sky AR. */
    val NightSky: ImageVector by lazy {
        icon("NightSky") {
            strokePath {
                moveTo(12f, 3.2f)
                lineTo(13.8f, 9.8f)
                lineTo(20.4f, 11.6f)
                lineTo(13.8f, 13.4f)
                lineTo(12f, 20.0f)
                lineTo(10.2f, 13.4f)
                lineTo(3.6f, 11.6f)
                lineTo(10.2f, 9.8f)
                close()
            }
            fillPath {
                circle(centerX = 18.5f, centerY = 5.5f, radius = 1.4f)
                circle(centerX = 5.5f, centerY = 18.5f, radius = 1.2f)
            }
        }
    }

    /** MilkyWay: galactic core and spiral nebular arms. */
    val MilkyWay: ImageVector by lazy {
        icon("MilkyWay") {
            strokePath {
                // Outer elliptical spiral orbit
                moveTo(3.6f, 15.6f)
                curveTo(2.4f, 10.8f, 6.2f, 5.2f, 12f, 4.4f)
                curveTo(17.8f, 3.6f, 21.6f, 8.4f, 20.4f, 13.2f)
                curveTo(19.2f, 17.6f, 14.8f, 20.4f, 9.8f, 19.6f)
                // Inner galactic swirl
                moveTo(8.5f, 14.2f)
                curveTo(7.8f, 11.4f, 9.6f, 8.4f, 13.2f, 8.0f)
                curveTo(16.4f, 7.6f, 17.8f, 10.4f, 16.6f, 13.4f)
            }
            fillPath {
                // Radiant galactic nucleus
                circle(centerX = 12.6f, centerY = 11.8f, radius = 2.0f)
            }
        }
    }

    /** Planetarium: opaque celestial dome / sky vault. */
    val Planetarium: ImageVector by lazy {
        icon("Planetarium") {
            strokePath {
                // Horizon baseline
                moveTo(3.0f, 18.5f)
                lineTo(21.0f, 18.5f)
                // Half-dome arc
                moveTo(4.2f, 18.5f)
                arcTo(7.8f, 7.8f, 0f, false, true, 19.8f, 18.5f)
                // Dome zenith finial / compass needle
                moveTo(12.0f, 6.0f)
                lineTo(12.0f, 10.5f)
            }
            fillPath {
                circle(centerX = 12.0f, centerY = 4.8f, radius = 1.2f)
            }
        }
    }

    /** Atmosphere: atmospheric horizon glow layer. */
    val Atmosphere: ImageVector by lazy {
        icon("Atmosphere") {
            strokePath {
                // Horizon baseline
                moveTo(2.4f, 18.5f)
                lineTo(21.6f, 18.5f)
                // Outer atmospheric scattering glow dome
                moveTo(3.8f, 18.5f)
                arcTo(8.2f, 8.2f, 0f, false, true, 20.2f, 18.5f)
                // Inner dense atmospheric glow layer
                moveTo(7.2f, 18.5f)
                arcTo(4.8f, 4.8f, 0f, false, true, 16.8f, 18.5f)
            }
        }
    }

    /** Constellation: connected stellar nodes / stick figures. */
    val Constellation: ImageVector by lazy {
        icon("Constellation") {
            strokePath {
                moveTo(5.2f, 16.8f)
                lineTo(9.6f, 9.2f)
                lineTo(16.4f, 12.4f)
                lineTo(19.2f, 5.6f)
            }
            fillPath {
                circle(centerX = 5.2f, centerY = 16.8f, radius = 2.0f)
                circle(centerX = 9.6f, centerY = 9.2f, radius = 2.0f)
                circle(centerX = 16.4f, centerY = 12.4f, radius = 2.0f)
                circle(centerX = 19.2f, centerY = 5.6f, radius = 2.0f)
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
