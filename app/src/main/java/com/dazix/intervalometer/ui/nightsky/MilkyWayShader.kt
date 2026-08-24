package com.dazix.intervalometer.ui.nightsky

import org.intellij.lang.annotations.Language

/**
 * AGSL (Android Graphics Shading Language) Hardware Ray-Casting Shader
 * for the Celestial Sky Dome & Milky Way background.
 *
 * Evaluated per-fragment on GPU hardware with 100% zero polygon clipping artifacts,
 * seamless 360-degree cylindrical wrap-around, and dynamic Red Vision monochromatic filtering.
 */
object MilkyWayShader {

    @Language("AGSL")
    const val SRC = """
        uniform shader milkyWayTexture;
        uniform float2 resolution;
        uniform float2 textureSize;
        uniform mat3 viewMatrix; // Combined rotation: M_enu2cel * R_sensor * M_disp
        uniform float fovFactor; // 1.0 / tan(effectiveFov_H / 2.0)
        uniform float isRedVision; // 1.0 for Red Vision Mode, 0.0 for Normal
        uniform float3 nightVisionTint; // RGB tint factor e.g. (1.0, 0.0, 0.0)

        half4 main(float2 fragCoord) {
            // 1. Normalize screen coordinates to [-1, 1] with Aspect Ratio correction
            vec2 uv = (fragCoord.xy / resolution.xy) * 2.0 - 1.0;
            uv.y *= (resolution.y / resolution.x);

            // 2. Create the 3D ray direction vector (Camera looks down the -Z axis)
            vec3 rayDir = normalize(vec3(uv.x, -uv.y, -fovFactor));

            // 3. Rotate the ray to Celestial Equatorial Coordinates (J2000.0)
            vec3 worldDir = viewMatrix * rayDir;

            // 4. Cartesian to Spherical (Right Ascension and Declination)
            // atan() naturally handles the 360-degree wrap-around (no seams!)
            float theta = atan(worldDir.z, worldDir.x);
            float phi = asin(clamp(worldDir.y, -1.0, 1.0));

            // 5. Map Spherical Coordinates to UV texture space [0.0, 1.0]
            float texU = (theta / 6.283185307179586) + 0.5;
            float texV = 0.5 - (phi / 3.141592653589793);

            // 6. Sample the texture (AGSL requires explicit pixel dimensions)
            vec2 sampleCoords = vec2(texU * textureSize.x, texV * textureSize.y);
            half4 color = milkyWayTexture.eval(sampleCoords);

            // 7. Night Vision (Astrophotography Night Mode) Filter
            // Extract luminance and map it to the active night vision theme tint
            if (isRedVision > 0.5) {
                float luminance = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                color = half4(luminance * 0.3 * nightVisionTint, color.a);
            }

            return color;
        }
    """
}
