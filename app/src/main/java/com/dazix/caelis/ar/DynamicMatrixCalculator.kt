package com.dazix.caelis.ar

import kotlin.math.cos
import kotlin.math.sin

/**
 * Pre-multiplies a Z-axis rotation matrix (incorporating magnetic declination and dynamic azimuth offset)
 * with the device sensor matrix to compute the True-World rotation matrix ($R_{\text{true}}$).
 *
 * All operations execute with 100% zero heap allocations on the 60 FPS render loop.
 */
object DynamicMatrixCalculator {

    /**
     * Pre-multiplies a Z-axis rotation matrix by angle [totalAzimuthDeg] with [sensorMatrix16]
     * into [outMatrix16].
     *
     * $$R_{\text{true}} = R_z(\delta + \Delta\alpha) \cdot R_{\text{sensor}}$$
     *
     * @param sensorMatrix16 4x4 device sensor matrix from SensorManager.
     * @param declinationDeg True-North magnetic declination in degrees ($\delta$).
     * @param azimuthOffsetDeg Dynamic cross-correlation azimuth offset in degrees ($\Delta\alpha$).
     * @param outMatrix16 Pre-allocated 16-element FloatArray to receive the result.
     */
    fun computeTrueWorldMatrix(
        sensorMatrix16: FloatArray,
        declinationDeg: Float,
        azimuthOffsetDeg: Float,
        outMatrix16: FloatArray,
    ) {
        require(sensorMatrix16.size >= 16) { "sensorMatrix16 must have at least 16 elements" }
        require(outMatrix16.size >= 16) { "outMatrix16 must have at least 16 elements" }

        val totalAzimuthDeg = declinationDeg + azimuthOffsetDeg
        if (kotlin.math.abs(totalAzimuthDeg) < 0.0001f) {
            System.arraycopy(sensorMatrix16, 0, outMatrix16, 0, 16)
            return
        }

        val angleRad = Math.toRadians(totalAzimuthDeg.toDouble())
        val cosA = cos(angleRad).toFloat()
        val sinA = sin(angleRad).toFloat()

        // Multiplying R_z(theta) * R_sensor
        // R_z(theta) = [ cosA, sinA, 0, 0 ]
        //              [-sinA, cosA, 0, 0 ]
        //              [    0,    0, 1, 0 ]
        //              [    0,    0, 0, 1 ]

        // Row 0
        outMatrix16[0] = cosA * sensorMatrix16[0] + sinA * sensorMatrix16[4]
        outMatrix16[1] = cosA * sensorMatrix16[1] + sinA * sensorMatrix16[5]
        outMatrix16[2] = cosA * sensorMatrix16[2] + sinA * sensorMatrix16[6]
        outMatrix16[3] = cosA * sensorMatrix16[3] + sinA * sensorMatrix16[7]

        // Row 1
        outMatrix16[4] = -sinA * sensorMatrix16[0] + cosA * sensorMatrix16[4]
        outMatrix16[5] = -sinA * sensorMatrix16[1] + cosA * sensorMatrix16[5]
        outMatrix16[6] = -sinA * sensorMatrix16[2] + cosA * sensorMatrix16[6]
        outMatrix16[7] = -sinA * sensorMatrix16[3] + cosA * sensorMatrix16[7]

        // Row 2 (Z row unchanged)
        outMatrix16[8] = sensorMatrix16[8]
        outMatrix16[9] = sensorMatrix16[9]
        outMatrix16[10] = sensorMatrix16[10]
        outMatrix16[11] = sensorMatrix16[11]

        // Row 3
        outMatrix16[12] = 0f
        outMatrix16[13] = 0f
        outMatrix16[14] = 0f
        outMatrix16[15] = 1f
    }
}
