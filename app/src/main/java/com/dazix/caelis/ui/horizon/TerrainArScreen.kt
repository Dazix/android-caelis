package com.dazix.caelis.ui.horizon

import android.graphics.RuntimeShader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dazix.caelis.ar.CameraPreview
import com.dazix.caelis.ui.nightsky.LandscapeShader

/**
 * 3-Layer Lifecycle-Safe AR Screen for Terrain Horizon Subsystem:
 * - Layer 0 (Bottom): [CameraPreview] live viewport
 * - Layer 1 (Middle): [HardwareTerrainShaderCanvas] AGSL shader (zero recomposition on offset updates)
 * - Layer 2 (Top): [HorizonLockIndicator] M3 Expressive HUD overlay
 */
@Composable
fun TerrainArScreen(
    onBack: () -> Unit = {},
    viewModel: HorizonViewModel = viewModel(),
    hasCameraPermission: Boolean = true,
    displayRotation: Int = 0,
    getLiveMatrix16: () -> FloatArray = { FloatArray(16) { if (it % 5 == 0) 1f else 0f } },
    getFovDegrees: () -> Float = { 65.0f },
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // Collect state with lifecycle awareness so processing pauses when backgrounded
    val offsetState by viewModel.offsetState.collectAsStateWithLifecycle()

    val runtimeShader = remember {
        runCatching { RuntimeShader(LandscapeShader.SRC) }.getOrNull()
    }
    val shaderBrush = remember(runtimeShader) {
        runtimeShader?.let { ShaderBrush(it) }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            viewModel.stopAlignmentStream()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Layer 0: Camera Preview Surface (Lowest layer)
        if (hasCameraPermission) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                lifecycleOwner = lifecycleOwner,
                isPortrait = true,
                onFovCalculated = { fov, includesZoom -> /* FOV callback */ },
                onCameraBound = { control, minZoom, maxZoom -> /* Camera control binding */ },
            )
        }

        // Layer 1: Hardware AGSL Ray-Casting Shader (Middle layer)
        // Passes a lambda provider so offset reads only happen in the draw pass
        if (runtimeShader != null && shaderBrush != null) {
            HardwareTerrainShaderCanvas(
                runtimeShader = runtimeShader,
                shaderBrush = shaderBrush,
                getViewMatrix16 = getLiveMatrix16,
                displayRotation = displayRotation,
                getFovHorizontalDegrees = getFovDegrees,
                offsetProvider = { viewModel.offsetState.value },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Layer 2: M3 Expressive Horizon Lock Indicator HUD (Top layer)
        HorizonLockIndicator(
            isLocked = offsetState.isLocked,
            residualDegrees = offsetState.residualDegrees,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp),
        )
    }
}
