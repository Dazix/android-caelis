package com.dazix.caelis.ui.horizon

import android.graphics.RuntimeShader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ShaderBrush
import com.dazix.caelis.domain.nightsky.CelestialMatrixCalculator
import kotlin.math.tan

/**
 * 60 FPS zero-allocation and zero-recomposition hardware AGSL terrain canvas.
 *
 * **CRITICAL PERFORMANCE GUARANTEE:**
 * [offsetProvider] is evaluated strictly within the Draw Phase ([onDrawBehind]).
 * When azimuth or elevation offsets update during real-time tracking, NO Compose
 * recompositions or relayout passes are triggered.
 */
@Composable
fun HardwareTerrainShaderCanvas(
    runtimeShader: RuntimeShader,
    shaderBrush: ShaderBrush,
    getViewMatrix16: () -> FloatArray,
    displayRotation: Int,
    getFovHorizontalDegrees: () -> Float,
    offsetProvider: () -> HorizonOffsetState,
    modifier: Modifier = Modifier,
) {
    // Pre-allocated matrix buffers for zero GC allocations per frame
    val matrix9Buffer = remember { FloatArray(9) }
    val terrainColor = remember { floatArrayOf(0.02f, 0.03f, 0.06f, 1.0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                onDrawBehind {
                    val w = size.width
                    val h = size.height
                    if (w <= 0f || h <= 0f) return@onDrawBehind

                    // 1. Read state strictly in draw pass
                    val offset = offsetProvider()

                    // 2. Compute camera projection parameters
                    val fovH = getFovHorizontalDegrees()
                    val halfFovHRad = (fovH * 0.5f * (Math.PI / 180.0)).toFloat()
                    val fovFactor = 1.0f / tan(halfFovHRad)

                    // 3. Compute 3x3 atmosphere view matrix from 4x4 sensor matrix
                    val sensorMatrix16 = getViewMatrix16()
                    CelestialMatrixCalculator.computeAtmosphereViewMatrix(
                        sensorMatrix16 = sensorMatrix16,
                        displayRotation = displayRotation,
                        outMatrix9 = matrix9Buffer,
                    )

                    // 4. Convert degrees to radians for shader uniforms
                    val azOffsetRad = Math.toRadians(offset.azimuthOffset.toDouble()).toFloat()
                    val elOffsetRad = Math.toRadians(offset.elevationOffset.toDouble()).toFloat()

                    // 5. Update RuntimeShader uniforms directly on GPU
                    runtimeShader.setFloatUniform("resolution", w, h)
                    runtimeShader.setFloatUniform("viewMatrix", matrix9Buffer)
                    runtimeShader.setFloatUniform("fovFactor", fovFactor)
                    runtimeShader.setFloatUniform("u_azimuthOffset", azOffsetRad)
                    runtimeShader.setFloatUniform("u_elevationOffset", elOffsetRad)
                    runtimeShader.setFloatUniform("useRealHorizon", 1.0f)
                    runtimeShader.setFloatUniform("terrainColor", terrainColor)

                    // 6. Execute hardware-accelerated draw
                    drawRect(brush = shaderBrush)
                }
            }
    )
}
