package com.dazix.caelis.ui.nightsky

import org.intellij.lang.annotations.Language

/**
 * AGSL (Android Graphics Shading Language) Hardware Ray-Casting Shader
 * for the Photorealistic Ground Landscape (Grassy field with tree line).
 *
 * Fixed to Earth ENU coordinates without celestial/LST rotation.
 * Seamless 360-degree cylindrical wrap-around and zero polygon clipping at Nadir (-90°).
 *
 * Two modes share the ray cast, because the ray cast is the expensive part and identical either way:
 *  - the stylised panorama, sampled from an equirectangular texture;
 *  - the real terrain skyline, read out of a 1440x1 profile strip and filled solid below.
 *
 * The real-horizon branch is what makes stars actually disappear behind a ridge instead of shining
 * through it, and it does so without a single polygon: the comparison is per-pixel.
 */
object LandscapeShader {

    /**
     * Width of the horizon profile strip, in texels. Must match
     * [com.dazix.caelis.domain.horizon.HorizonProfile.SAMPLE_COUNT].
     */
    const val PROFILE_WIDTH = 1440

    @Language("AGSL")
    const val SRC = """
        uniform shader landscapeTexture;
        uniform shader horizonProfile;  // 1440x1 strip, elevation encoded 16-bit across R and G
        uniform float2 resolution;
        uniform float2 textureSize;
        uniform mat3 viewMatrix; // Sensor R^T matrix (World ENU space)
        uniform float fovFactor; // 1.0 / tan(effectiveFov_H / 2.0)
        uniform float isRedVision; // 1.0 for Night Mode, 0.0 for Normal
        uniform float3 nightVisionTint;
        uniform float useRealHorizon; // 1.0 to read the profile strip instead of the panorama
        uniform float4 terrainColor;  // Fill below the real skyline, straight RGBA

        const float PROFILE_TEXELS = 1440.0;

        // Undoes the 16-bit packing done on the CPU side: n = R * 256 + G over the full range,
        // mapped onto [-128, +128] degrees. 0.0039 degrees per step, far finer than the profile
        // itself resolves, so the encoding is never the thing that limits accuracy.
        float decodeElevation(half4 texel) {
            float n = texel.r * 255.0 * 256.0 + texel.g * 255.0;
            return (n / 65535.0) * 256.0 - 128.0;
        }

        // Takes a texel coordinate, not a normalised one, because the strip and the panorama do not
        // share a convention: texel 0 of the strip is due north, while the panorama has north in the
        // middle of the image. Reading the strip with the panorama's `texU` drew the whole skyline
        // half a turn out — a ridge due north appeared due south — and looked entirely plausible,
        // because a horizon is a horizon whichever way you are facing.
        //
        // The blend between neighbours is done here rather than by the sampler: hardware filtering
        // would interpolate the packed bytes, and an interpolated R channel across a carry boundary
        // is a wild number, not a halfway elevation.
        float terrainElevationAt(float texel) {
            float i0 = floor(texel);
            float f = texel - i0;
            float x0 = mod(i0, PROFILE_TEXELS) + 0.5;
            float x1 = mod(i0 + 1.0, PROFILE_TEXELS) + 0.5;
            float e0 = decodeElevation(horizonProfile.eval(float2(x0, 0.5)));
            float e1 = decodeElevation(horizonProfile.eval(float2(x1, 0.5)));
            return mix(e0, e1, f);
        }

        half4 applyNightVision(half4 color) {
            if (isRedVision > 0.5 && color.a > 0.0) {
                float luminance = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                return half4(luminance * 0.15 * nightVisionTint, color.a);
            }
            return color;
        }

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
            // The half-turn offset is the *panorama's* convention: that asset has north in the centre
            // column. The profile strip below starts at north instead and gets its own coordinate.
            float texU = (theta / 6.283185307179586) + 0.5;
            float texV = 0.5 - (phi / 3.141592653589793);

            if (useRealHorizon > 0.5) {
                // Straight from azimuth to strip texel: theta = 0 is north is texel 0.
                float profileTexel = mod(theta * (PROFILE_TEXELS / 6.283185307179586), PROFILE_TEXELS);
                float terrainAlt = terrainElevationAt(profileTexel);
                float phiDeg = phi * 57.29577951308232;
                // Nothing at all above the skyline: the sky has to stay the sky, and a ridge with a
                // faint wash over it would be worse than no terrain layer.
                if (phiDeg >= terrainAlt) {
                    return half4(0.0);
                }
                return applyNightVision(half4(terrainColor));
            }

            // 6. Sample the texture
            vec2 sampleCoords = vec2(texU * textureSize.x, texV * textureSize.y);
            half4 color = landscapeTexture.eval(sampleCoords);

            // 7. Night Vision (Night Mode) Filter
            return applyNightVision(color);
        }
    """
}
