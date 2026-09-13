package com.dazix.caelis.ui.horizon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dazix.caelis.ar.FrameGrabber
import com.dazix.caelis.ar.SkylineAligner
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.horizon.SkylineMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel managing real-time and simulated camera-to-terrain cross-correlation alignment.
 */
class HorizonViewModel : ViewModel() {

    private val _offsetState = MutableStateFlow(HorizonOffsetState())
    val offsetState: StateFlow<HorizonOffsetState> = _offsetState.asStateFlow()

    private var alignmentJob: Job? = null

    /**
     * Starts continuous background cross-correlation stream from CameraX Y-luma frames.
     */
    fun startAlignmentStream(
        frameGrabber: FrameGrabber,
        getMatrixForTimestamp: (timestampNs: Long) -> FloatArray,
        getFovDegrees: () -> Float,
        getProfile: () -> HorizonProfile?,
        viewportWidth: Float,
        viewportHeight: Float,
        displayRotation: Int,
        pollIntervalMs: Long = 250L,
    ) {
        alignmentJob?.cancel()
        alignmentJob = viewModelScope.launch {
            while (isActive) {
                val profile = getProfile()
                if (profile == null || !profile.isUsable) {
                    delay(500)
                    continue
                }

                frameGrabber.grabNext { frame ->
                    viewModelScope.launch {
                        val rotationMatrix = getMatrixForTimestamp(frame.timestampNs)
                        val match = withContext(Dispatchers.Default) {
                            SkylineAligner.align(
                                frame = frame,
                                viewportWidth = viewportWidth,
                                viewportHeight = viewportHeight,
                                rotationMatrix = rotationMatrix,
                                displayRotation = displayRotation,
                                fovHorizontalDegrees = getFovDegrees(),
                                profile = profile,
                            )
                        }

                        if (match != null) {
                            val isLocked = match.residualDegrees <= SkylineMatcher.MAX_ACCEPTABLE_RESIDUAL_DEGREES
                            _offsetState.value = HorizonOffsetState(
                                azimuthOffset = match.azimuthOffsetDegrees.toFloat(),
                                elevationOffset = match.elevationOffsetDegrees.toFloat(),
                                isLocked = isLocked,
                                residualDegrees = match.residualDegrees.toFloat(),
                            )
                        } else {
                            if (_offsetState.value.isLocked) {
                                _offsetState.value = _offsetState.value.copy(isLocked = false)
                            }
                        }
                    }
                }
                delay(pollIntervalMs)
            }
        }
    }

    /**
     * Simulates the data stream from [SkylineAligner] for testing and UI preview.
     */
    fun simulateAlignment(
        azimuthOffset: Float,
        elevationOffset: Float,
        residualDegrees: Float,
    ) {
        val isLocked = residualDegrees <= SkylineMatcher.MAX_ACCEPTABLE_RESIDUAL_DEGREES
        _offsetState.value = HorizonOffsetState(
            azimuthOffset = azimuthOffset,
            elevationOffset = elevationOffset,
            isLocked = isLocked,
            residualDegrees = residualDegrees,
        )
    }

    /**
     * Resets the alignment state to default unaligned values.
     */
    fun resetAlignment() {
        _offsetState.value = HorizonOffsetState()
    }

    fun stopAlignmentStream() {
        alignmentJob?.cancel()
        alignmentJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopAlignmentStream()
    }
}
