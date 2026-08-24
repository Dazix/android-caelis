package com.dazix.intervalometer.ui.nightsky

import org.intellij.lang.annotations.Language

/**
 * AGSL (Android Graphics Shading Language) Hardware Ray-Casting Shader
 * for the Photorealistic Ground Landscape (Grassy field with tree line).
 *
 * Fixed to Earth ENU coordinates without celestial/LST rotation.
 * Seamless 360-degree cylindrical wrap-around and zero polygon clipping at Nadir (-90°).
 */
object LandscapeShader {

    @Language("AGSL")
    const val SRC = """
        uniform shader landscapeTexture;
        uniform float2 resolution;
        uniform float2 textureSize;
        uniform mat3 viewMatrix; // Sensor R^T matrix (World ENU space)
        uniform float fovFactor; // 1.0 / tan(effectiveFov_H / 2.0)
        uniform float isRedVision; // 1.0 for Night Mode, 0.0 for Normal
        uniform float3 nightVisionTint;

        half4 main(float2 fragCoord) {
            // 1. Normalize screen coordinates to [-1, 1] with Aspect Ratio correction
            vec2 uv = (fragCoord.xy / resolution.xy) * 2.0 - 1.0;
            uv.y *= (resolution.y / resolution.x);

            // 2. 3D ray direction (Camera looks down -Z)
            vec3 rayDir = normalize(vec3(uv.x, -uv.y, -fovFactor));

            // 3. Rotate ray to World ENU Coordinates (East, North, Up)
            vec3 worldDir = viewMatrix * rayDir;

            // 4. Cartesian to Spherical (Azimuth theta around Up/Z, Elevation phi above horizon)
            float theta = atan(worldDir.x, worldDir.y);
            float phi = asin(clamp(worldDir.z, -1.0, 1.0));

            // 5. Map Spherical Coordinates to UV texture space [0.0, 1.0]
            float texU = (theta / 6.283185307179586) + 0.5;
            float texV = 0.5 - (phi / 3.141592653589793);

            // 6. Sample the texture
            vec2 sampleCoords = vec2(texU * textureSize.x, texV * textureSize.y);
            half4 color = landscapeTexture.eval(sampleCoords);

            // 7. Night Vision (Night Mode) Filter
            if (isRedVision > 0.5 && color.a > 0.0) {
                float luminance = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                color = half4(luminance * 0.15 * nightVisionTint, color.a);
            }

            return color;
        }
    """
}
