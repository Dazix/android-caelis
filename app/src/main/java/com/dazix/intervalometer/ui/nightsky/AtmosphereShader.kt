package com.dazix.intervalometer.ui.nightsky

import org.intellij.lang.annotations.Language

/**
 * AGSL (Android Graphics Shading Language) Hardware Ray-Casting Shader
 * for the Atmospheric Horizon Glow.
 *
 * Ray-casts every pixel to World ENU (East, North, Up) coordinates to accurately
 * calculate physical elevation, ensuring the atmospheric glow hugs the 3D horizon
 * regardless of device roll, pitch, or display orientation.
 */
object AtmosphereShader {

    @Language("AGSL")
    const val SRC = """
        uniform float2 resolution;
        uniform mat3 viewMatrix; // Sensor R^T matrix (World ENU space)
        uniform float fovFactor; // Calculated from CameraFovCalculator: 1.0 / tan(halfFovH)
        uniform float isRedVision; // 1.0 for Night Mode, 0.0 for Normal

        // Colors for the gradient
        const half4 colorBottom = half4(0.1, 0.25, 0.45, 1.0); // Deep twilight blue
        const half4 colorTop = half4(0.0, 0.0, 0.0, 0.0);      // Fully transparent

        half4 main(float2 fragCoord) {
            // 1. Normalize screen coordinates
            vec2 uv = (fragCoord.xy / resolution.xy) * 2.0 - 1.0;
            uv.y *= (resolution.y / resolution.x);

            // 2. 3D ray direction (Camera looks down -Z)
            vec3 rayDir = normalize(vec3(uv.x, -uv.y, -fovFactor));

            // 3. Rotate ray to World ENU Coordinates (East, North, Up)
            vec3 worldDir = viewMatrix * rayDir;

            // 4. Extract Elevation (Up axis is Z in ENU mapping)
            float sinElevation = worldDir.z;

            // Discard pixels below the mathematical horizon
            if (sinElevation < 0.0) return half4(0.0);

            // 5. Calculate atmospheric scattering (Glow height approx. 15 degrees)
            // sin(15°) ≈ 0.258
            float maxElevationSin = 0.258;
            float factor = clamp(1.0 - (sinElevation / maxElevationSin), 0.0, 1.0);
            
            // Non-linear exponential decay for realistic light scattering
            factor = pow(factor, 3.0);

            // 6. Blend colors
            half4 color = mix(colorTop, colorBottom, factor);

            // 7. Red Vision (Night Mode) Filter
            if (isRedVision > 0.5) {
                float luminance = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                // Mute the atmosphere significantly in Red Mode to avoid overpowering stars
                color = half4(luminance * 0.4, 0.0, 0.0, color.a * 0.4);
            }
            
            return color;
        }
    """
}
