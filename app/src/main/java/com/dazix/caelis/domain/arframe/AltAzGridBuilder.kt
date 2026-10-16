package com.dazix.caelis.domain.arframe

import kotlin.math.cos
import kotlin.math.sin

/**
 * The alt-az sky grid as ENU line segments — the geometry `SkyDomeRenderer.drawSkyDomeGrid` used to
 * project point by point every frame, now emitted once for the GPU.
 *
 * Same lattice and weights as the Canvas grid:
 * - 12 meridians every 30 deg from -85 to +85 deg in 5 deg steps; N/E/S/W are major (1.2 px), the
 *   rest minor (0.8 px);
 * - the horizon circle in 2 deg steps (1.8 px, horizon colour);
 * - altitude circles at -30, -15, +15, +30, +45, +60, +75 deg in 5 deg steps; 30 and 60 are
 *   major (1.0 px), the rest minor (0.7 px).
 * Widths are raw pixels, as they were on the Canvas.
 */
object AltAzGridBuilder {

    enum class Role { HORIZON, MAJOR, MINOR }

    fun interface SegmentSink {
        fun segment(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, widthPx: Float, role: Role)
    }

    val MERIDIAN_AZIMUTHS = doubleArrayOf(0.0, 30.0, 60.0, 90.0, 120.0, 150.0, 180.0, 210.0, 240.0, 270.0, 300.0, 330.0)
    val ALTITUDE_LEVELS = doubleArrayOf(-30.0, -15.0, 15.0, 30.0, 45.0, 60.0, 75.0)

    /** 12 meridians x 34 + 180 horizon + 7 rings x 72. */
    const val SEGMENT_COUNT = 12 * 34 + 180 + 7 * 72

    fun build(sink: SegmentSink) {
        val a = FloatArray(3)
        val b = FloatArray(3)
        for (azimuth in MERIDIAN_AZIMUTHS) {
            val major = azimuth % 90.0 == 0.0
            for (i in 0 until 34) {
                direction(azimuth, -85.0 + i * 5.0, a)
                direction(azimuth, -85.0 + (i + 1) * 5.0, b)
                sink.segment(a[0], a[1], a[2], b[0], b[1], b[2], if (major) 1.2f else 0.8f, if (major) Role.MAJOR else Role.MINOR)
            }
        }
        for (i in 0 until 180) {
            direction(i * 2.0, 0.0, a)
            direction(((i + 1) % 180) * 2.0, 0.0, b)
            sink.segment(a[0], a[1], a[2], b[0], b[1], b[2], 1.8f, Role.HORIZON)
        }
        for (elevation in ALTITUDE_LEVELS) {
            val major = elevation == 30.0 || elevation == 60.0
            for (i in 0 until 72) {
                direction(i * 5.0, elevation, a)
                direction(((i + 1) % 72) * 5.0, elevation, b)
                sink.segment(a[0], a[1], a[2], b[0], b[1], b[2], if (major) 1.0f else 0.7f, if (major) Role.MAJOR else Role.MINOR)
            }
        }
    }

    /** ENU unit vector for an azimuth (clockwise from north) and elevation, both in degrees. */
    fun direction(azimuthDeg: Double, elevationDeg: Double, out3: FloatArray) {
        val az = Math.toRadians(azimuthDeg)
        val el = Math.toRadians(elevationDeg)
        out3[0] = (sin(az) * cos(el)).toFloat()
        out3[1] = (cos(az) * cos(el)).toFloat()
        out3[2] = sin(el).toFloat()
    }
}
