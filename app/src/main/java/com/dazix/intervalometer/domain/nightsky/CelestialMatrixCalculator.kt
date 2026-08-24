package com.dazix.intervalometer.domain.nightsky

import kotlin.math.cos
import kotlin.math.sin

/**
 * High-performance, deterministic, zero-allocation calculator for the 3x3 Celestial View Matrix
 * used by the AGSL RuntimeShader background pipeline.
 *
 * Constructs the combined rotation matrix:
 * ViewMatrix = M_enu2cel * R_sensor * M_disp
 *
 * which transforms camera ray direction vectors directly into Equatorial Cartesian coordinates (J2000.0).
 */
object CelestialMatrixCalculator {

    private const val DEG_TO_RAD = Math.PI / 180.0

    // Reusable intermediate 3x3 matrices to prevent heap allocations
    private val tempAB = FloatArray(9)

    /**
     * Computes the 3x3 column-major view matrix for the AGSL shader.
     *
     * @param sensorMatrix16 4x4 or 3x3 sensor rotation matrix (from OrientationTracker).
     * @param lstDegrees Local Sidereal Time in degrees [0.0, 360.0).
     * @param latitudeDegrees Observer latitude in degrees [-90.0, +90.0].
     * @param displayRotation Android Surface rotation (Surface.ROTATION_0, ROTATION_90, etc.).
     * @param outMatrix9 Pre-allocated FloatArray(9) to store the 3x3 column-major matrix.
     */
    @Synchronized
    fun computeViewMatrix(
        sensorMatrix16: FloatArray,
        lstDegrees: Double,
        latitudeDegrees: Double,
        displayRotation: Int,
        outMatrix9: FloatArray,
    ) {
        val lstRad = lstDegrees * DEG_TO_RAD
        val latRad = latitudeDegrees * DEG_TO_RAD

        val sinLst = sin(lstRad).toFloat()
        val cosLst = cos(lstRad).toFloat()
        val sinLat = sin(latRad).toFloat()
        val cosLat = cos(latRad).toFloat()

        // M_enu2cel (3x3) row-major:
        // A00 = -sinLst,            A01 = -sinLat * cosLst, A02 = cosLat * cosLst
        // A10 = 0,                  A11 = cosLat,           A12 = sinLat
        // A20 = cosLst,             A21 = -sinLat * sinLst, A22 = cosLat * sinLst
        val a00 = -sinLst
        val a01 = -sinLat * cosLst
        val a02 = cosLat * cosLst

        val a10 = 0.0f
        val a11 = cosLat
        val a12 = sinLat

        val a20 = cosLst
        val a21 = -sinLat * sinLst
        val a22 = cosLat * sinLst

        // Extract R_sensor (3x3) from 4x4 or 3x3 sensor matrix:
        // R transforms Device -> World ENU: V_world = R * V_dev
        val b00: Float
        val b01: Float
        val b02: Float
        val b10: Float
        val b11: Float
        val b12: Float
        val b20: Float
        val b21: Float
        val b22: Float

        if (sensorMatrix16.size >= 16) {
            b00 = sensorMatrix16[0]; b01 = sensorMatrix16[1]; b02 = sensorMatrix16[2]
            b10 = sensorMatrix16[4]; b11 = sensorMatrix16[5]; b12 = sensorMatrix16[6]
            b20 = sensorMatrix16[8]; b21 = sensorMatrix16[9]; b22 = sensorMatrix16[10]
        } else if (sensorMatrix16.size >= 9) {
            b00 = sensorMatrix16[0]; b01 = sensorMatrix16[1]; b02 = sensorMatrix16[2]
            b10 = sensorMatrix16[3]; b11 = sensorMatrix16[4]; b12 = sensorMatrix16[5]
            b20 = sensorMatrix16[6]; b21 = sensorMatrix16[7]; b22 = sensorMatrix16[8]
        } else {
            b00 = 1f; b01 = 0f; b02 = 0f
            b10 = 0f; b11 = 1f; b12 = 0f
            b20 = 0f; b21 = 0f; b22 = 1f
        }

        // AB = M_enu2cel * R_sensor (3x3 row-major)
        // Row 0
        val ab00 = a00 * b00 + a01 * b10 + a02 * b20
        val ab01 = a00 * b01 + a01 * b11 + a02 * b21
        val ab02 = a00 * b02 + a01 * b12 + a02 * b22

        // Row 1
        val ab10 = a10 * b00 + a11 * b10 + a12 * b20
        val ab11 = a10 * b01 + a11 * b11 + a12 * b21
        val ab12 = a10 * b02 + a11 * b12 + a12 * b22

        // Row 2
        val ab20 = a20 * b00 + a21 * b10 + a22 * b20
        val ab21 = a20 * b01 + a21 * b11 + a22 * b21
        val ab22 = a20 * b02 + a21 * b12 + a22 * b22

        // Apply M_disp (Screen -> Device) based on displayRotation:
        // M_view = (AB) * M_disp, formatted into column-major outMatrix9:
        // Column 0: outMatrix9[0..2]
        // Column 1: outMatrix9[3..5]
        // Column 2: outMatrix9[6..8]
        when (displayRotation) {
            1 -> { // ROTATION_90: Col 0 = Col 1 of AB, Col 1 = -Col 0 of AB, Col 2 = Col 2 of AB
                outMatrix9[0] = ab01
                outMatrix9[1] = ab11
                outMatrix9[2] = ab21

                outMatrix9[3] = -ab00
                outMatrix9[4] = -ab10
                outMatrix9[5] = -ab20

                outMatrix9[6] = ab02
                outMatrix9[7] = ab12
                outMatrix9[8] = ab22
            }
            2 -> { // ROTATION_180: Col 0 = -Col 0 of AB, Col 1 = -Col 1 of AB, Col 2 = Col 2 of AB
                outMatrix9[0] = -ab00
                outMatrix9[1] = -ab10
                outMatrix9[2] = -ab20

                outMatrix9[3] = -ab01
                outMatrix9[4] = -ab11
                outMatrix9[5] = -ab21

                outMatrix9[6] = ab02
                outMatrix9[7] = ab12
                outMatrix9[8] = ab22
            }
            3 -> { // ROTATION_270: Col 0 = -Col 1 of AB, Col 1 = Col 0 of AB, Col 2 = Col 2 of AB
                outMatrix9[0] = -ab01
                outMatrix9[1] = -ab11
                outMatrix9[2] = -ab21

                outMatrix9[3] = ab00
                outMatrix9[4] = ab10
                outMatrix9[5] = ab20

                outMatrix9[6] = ab02
                outMatrix9[7] = ab12
                outMatrix9[8] = ab22
            }
            else -> { // ROTATION_0: Col 0 = Col 0 of AB, Col 1 = Col 1 of AB, Col 2 = Col 2 of AB
                outMatrix9[0] = ab00
                outMatrix9[1] = ab10
                outMatrix9[2] = ab20

                outMatrix9[3] = ab01
                outMatrix9[4] = ab11
                outMatrix9[5] = ab21

                outMatrix9[6] = ab02
                outMatrix9[7] = ab12
                outMatrix9[8] = ab22
            }
        }
    }

    /**
     * Computes the 3x3 column-major view matrix for the Earth-fixed AGSL Atmosphere shader.
     *
     * Constructs the transformation:
     * ViewMatrix = R_sensor * M_disp
     *
     * Transforms camera ray direction vectors directly into Earth ENU world coordinates (East, North, Up)
     * without applying celestial LST rotation.
     *
     * @param sensorMatrix16 4x4 or 3x3 sensor rotation matrix (from OrientationTracker).
     * @param displayRotation Android Surface rotation (Surface.ROTATION_0, ROTATION_90, etc.).
     * @param outMatrix9 Pre-allocated FloatArray(9) to store the 3x3 column-major matrix.
     */
    fun computeAtmosphereViewMatrix(
        sensorMatrix16: FloatArray,
        displayRotation: Int,
        outMatrix9: FloatArray,
    ) {
        val b00: Float
        val b01: Float
        val b02: Float
        val b10: Float
        val b11: Float
        val b12: Float
        val b20: Float
        val b21: Float
        val b22: Float

        if (sensorMatrix16.size >= 16) {
            b00 = sensorMatrix16[0]; b01 = sensorMatrix16[1]; b02 = sensorMatrix16[2]
            b10 = sensorMatrix16[4]; b11 = sensorMatrix16[5]; b12 = sensorMatrix16[6]
            b20 = sensorMatrix16[8]; b21 = sensorMatrix16[9]; b22 = sensorMatrix16[10]
        } else if (sensorMatrix16.size >= 9) {
            b00 = sensorMatrix16[0]; b01 = sensorMatrix16[1]; b02 = sensorMatrix16[2]
            b10 = sensorMatrix16[3]; b11 = sensorMatrix16[4]; b12 = sensorMatrix16[5]
            b20 = sensorMatrix16[6]; b21 = sensorMatrix16[7]; b22 = sensorMatrix16[8]
        } else {
            b00 = 1f; b01 = 0f; b02 = 0f
            b10 = 0f; b11 = 1f; b12 = 0f
            b20 = 0f; b21 = 0f; b22 = 1f
        }

        when (displayRotation) {
            1 -> { // ROTATION_90
                outMatrix9[0] = b01
                outMatrix9[1] = b11
                outMatrix9[2] = b21

                outMatrix9[3] = -b00
                outMatrix9[4] = -b10
                outMatrix9[5] = -b20

                outMatrix9[6] = b02
                outMatrix9[7] = b12
                outMatrix9[8] = b22
            }
            2 -> { // ROTATION_180
                outMatrix9[0] = -b00
                outMatrix9[1] = -b10
                outMatrix9[2] = -b20

                outMatrix9[3] = -b01
                outMatrix9[4] = -b11
                outMatrix9[5] = -b21

                outMatrix9[6] = b02
                outMatrix9[7] = b12
                outMatrix9[8] = b22
            }
            3 -> { // ROTATION_270
                outMatrix9[0] = -b01
                outMatrix9[1] = -b11
                outMatrix9[2] = -b21

                outMatrix9[3] = b00
                outMatrix9[4] = b10
                outMatrix9[5] = b20

                outMatrix9[6] = b02
                outMatrix9[7] = b12
                outMatrix9[8] = b22
            }
            else -> { // ROTATION_0
                outMatrix9[0] = b00
                outMatrix9[1] = b10
                outMatrix9[2] = b20

                outMatrix9[3] = b01
                outMatrix9[4] = b11
                outMatrix9[5] = b21

                outMatrix9[6] = b02
                outMatrix9[7] = b12
                outMatrix9[8] = b22
            }
        }
    }

    /**
     * Constructs a 100% orthonormal 4x4 rotation matrix in Earth ENU coordinates for a virtual camera
     * directly targeted at Horizontal (Azimuth, Elevation) coordinates with 0 roll.
     *
     * In this matrix:
     * - Optical axis -Z_dev points along: [sin(Az)*cos(El), cos(Az)*cos(El), sin(El)] (Forward)
     * - Right axis +X_dev points along: [cos(Az), -sin(Az), 0]
     * - Up axis +Y_dev points along: [-sin(Az)*sin(El), -cos(Az)*sin(El), cos(El)]
     *
     * @param azDeg Azimuth in degrees [0.0, 360.0) from North.
     * @param elDeg Elevation in degrees [-90.0, +90.0].
     * @param outMatrix16 FloatArray of size at least 16 to receive the 4x4 matrix.
     */
    fun computeManualRotationMatrix(
        azDeg: Double,
        elDeg: Double,
        outMatrix16: FloatArray,
    ) {
        val azRad = azDeg * DEG_TO_RAD
        val elRad = elDeg.coerceIn(-89.9, 89.9) * DEG_TO_RAD

        val sinA = sin(azRad).toFloat()
        val cosA = cos(azRad).toFloat()
        val sinE = sin(elRad).toFloat()
        val cosE = cos(elRad).toFloat()

        // Col 0: +X_dev (Right) = [cosA, -sinA, 0, 0]
        outMatrix16[0] = cosA
        outMatrix16[4] = -sinA
        outMatrix16[8] = 0f
        outMatrix16[12] = 0f

        // Col 1: +Y_dev (Up) = [-sinA * sinE, -cosA * sinE, cosE, 0]
        outMatrix16[1] = -sinA * sinE
        outMatrix16[5] = -cosA * sinE
        outMatrix16[9] = cosE
        outMatrix16[13] = 0f

        // Col 2: +Z_dev (Backward) = -v_cam = [-sinA * cosE, -cosA * cosE, -sinE, 0]
        outMatrix16[2] = -sinA * cosE
        outMatrix16[6] = -cosA * cosE
        outMatrix16[10] = -sinE
        outMatrix16[14] = 0f

        // Col 3: Origin = [0, 0, 0, 1]
        outMatrix16[3] = 0f
        outMatrix16[7] = 0f
        outMatrix16[11] = 0f
        outMatrix16[15] = 1f
    }
}

