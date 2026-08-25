package com.dazix.intervalometer.ui.solar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dazix.intervalometer.ar.CameraFovCalculator
import com.dazix.intervalometer.ar.DeviceOrientation
import com.dazix.intervalometer.ar.OrientationTracker
import com.dazix.intervalometer.ar.SolarLocationProvider
import com.dazix.intervalometer.data.SettingsStore
import com.dazix.intervalometer.domain.solar.BubbleInterval
import com.dazix.intervalometer.domain.solar.GeoLocation
import com.dazix.intervalometer.domain.solar.LunarCoordinates
import com.dazix.intervalometer.domain.solar.LunarPositionCalculator
import com.dazix.intervalometer.domain.solar.LunarTrajectory
import com.dazix.intervalometer.domain.solar.LunarTrajectoryPlanner
import com.dazix.intervalometer.domain.solar.SolarCoordinates
import com.dazix.intervalometer.domain.solar.SolarPositionCalculator
import com.dazix.intervalometer.domain.solar.SolarTrajectory
import com.dazix.intervalometer.domain.solar.SolarTrajectoryPlanner
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SunArUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val isToday: Boolean = true,
    val bubbleInterval: BubbleInterval = BubbleInterval.ONE_HOUR,
    val location: GeoLocation = GeoLocation.DEFAULT,
    val hasGpsFix: Boolean = false,
    val trajectory: SolarTrajectory = SolarTrajectoryPlanner.planTrajectory(
        LocalDate.now(),
        GeoLocation.DEFAULT,
        BubbleInterval.ONE_HOUR,
    ),
    val lunarTrajectory: LunarTrajectory = LunarTrajectoryPlanner.planTrajectory(
        LocalDate.now(),
        GeoLocation.DEFAULT,
        BubbleInterval.ONE_HOUR,
    ),
    val currentSunPosition: SolarCoordinates = SolarCoordinates(
        azimuthDegrees = 0.0,
        elevationDegrees = 0.0,
    ),
    val currentMoonPosition: LunarCoordinates = LunarCoordinates(
        azimuthDegrees = 0.0,
        elevationDegrees = 0.0,
        distanceKm = 384400.0,
        illuminationFraction = 0.5,
    ),
    val keepScreenOn: Boolean = false,
    val isCalibrated: Boolean = false,
    val cameraFovHorizontal: Float = CameraFovCalculator.DEFAULT_PORTRAIT_FOV_DEGREES,
    val zoomRatio: Float = 1.0f,
)

@OptIn(FlowPreview::class)
class SunArViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val orientationTracker = OrientationTracker(application)
    private val locationProvider = SolarLocationProvider(application)

    private val selectedDateState = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = selectedDateState.asStateFlow()

    private val bubbleIntervalState = MutableStateFlow(BubbleInterval.ONE_HOUR)
    val bubbleInterval: StateFlow<BubbleInterval> = bubbleIntervalState.asStateFlow()

    private val baseCameraFovState = MutableStateFlow(CameraFovCalculator.DEFAULT_PORTRAIT_FOV_DEGREES)
    private val zoomRatioState = MutableStateFlow(1.0f)
    val zoomRatio: StateFlow<Float> = zoomRatioState.asStateFlow()

    // Dynamic effective FOV factoring in pinch-to-zoom
    val cameraFovHorizontal: StateFlow<Float> = combine(baseCameraFovState, zoomRatioState) { baseFov, zoom ->
        CameraFovCalculator.computeZoomedFov(baseFov, zoom).coerceIn(5.0f, 140.0f)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CameraFovCalculator.DEFAULT_PORTRAIT_FOV_DEGREES)

    val orientation: StateFlow<DeviceOrientation?> = orientationTracker.orientation
    val location: StateFlow<GeoLocation> = locationProvider.location
    val hasGpsFix: StateFlow<Boolean> = locationProvider.hasGpsFix

    val keepScreenOn: StateFlow<Boolean> = settingsStore.sunArKeepScreenOn.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        false,
    )

    val isSensorTrackingPaused: StateFlow<Boolean> = orientationTracker.isSensorTrackingPaused.asStateFlow()

    val panAzimuthOffset = MutableStateFlow(0f)
    val panPitchOffset = MutableStateFlow(0f)
    val isCalibrated: StateFlow<Boolean> = combine(isSensorTrackingPaused, panAzimuthOffset, panPitchOffset) { paused, az, pitch ->
        paused || az != 0f || pitch != 0f
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val currentSunPosState = MutableStateFlow(
        SolarPositionCalculator.computePosition(Instant.now(), locationProvider.location.value)
    )
    val currentSunPosition: StateFlow<SolarCoordinates> = currentSunPosState.asStateFlow()

    private val currentMoonPosState = MutableStateFlow(
        LunarPositionCalculator.computePosition(Instant.now(), locationProvider.location.value)
    )
    val currentMoonPosition: StateFlow<LunarCoordinates> = currentMoonPosState.asStateFlow()

    val trajectory: StateFlow<SolarTrajectory> = combine(
        selectedDateState,
        locationProvider.location,
        bubbleIntervalState,
    ) { date, loc, interval ->
        SolarTrajectoryPlanner.planTrajectory(date, loc, interval, ZoneId.systemDefault())
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SolarTrajectoryPlanner.planTrajectory(LocalDate.now(), GeoLocation.DEFAULT, BubbleInterval.ONE_HOUR)
    )

    val lunarTrajectory: StateFlow<LunarTrajectory> = combine(
        selectedDateState,
        locationProvider.location,
        bubbleIntervalState,
    ) { date, loc, interval ->
        LunarTrajectoryPlanner.planTrajectory(date, loc, interval, ZoneId.systemDefault())
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        LunarTrajectoryPlanner.planTrajectory(LocalDate.now(), GeoLocation.DEFAULT, BubbleInterval.ONE_HOUR)
    )

    val uiState: StateFlow<SunArUiState> = combine(
        selectedDateState,
        bubbleIntervalState,
        locationProvider.location,
        locationProvider.hasGpsFix,
        trajectory,
        lunarTrajectory,
        currentSunPosState,
        currentMoonPosState,
        keepScreenOn,
        isCalibrated,
        cameraFovHorizontal,
        zoomRatioState,
    ) { args: Array<Any?> ->
        val date = args[0] as LocalDate
        val interval = args[1] as BubbleInterval
        val loc = args[2] as GeoLocation
        val gps = args[3] as Boolean
        val traj = args[4] as SolarTrajectory
        val lTraj = args[5] as LunarTrajectory
        val sunPos = args[6] as SolarCoordinates
        val moonPos = args[7] as LunarCoordinates
        val keepOn = args[8] as Boolean
        val calib = args[9] as Boolean
        val fov = args[10] as Float
        val zoom = args[11] as Float

        SunArUiState(
            selectedDate = date,
            isToday = date == LocalDate.now(),
            bubbleInterval = interval,
            location = loc,
            hasGpsFix = gps,
            trajectory = traj,
            lunarTrajectory = lTraj,
            currentSunPosition = sunPos,
            currentMoonPosition = moonPos,
            keepScreenOn = keepOn,
            isCalibrated = calib,
            cameraFovHorizontal = fov,
            zoomRatio = zoom,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SunArUiState())

    init {
        // Initialize user Alt-Azimuth offsets from settings store
        viewModelScope.launch {
            val initialAz = settingsStore.sunArAzimuthOffset.firstOrNull() ?: 0f
            val initialPitch = settingsStore.sunArElevationOffset.firstOrNull() ?: 0f
            panAzimuthOffset.value = initialAz
            panPitchOffset.value = initialPitch
            orientationTracker.setPanOffsets(initialAz, initialPitch)
        }

        // Debounce saving pan offsets to eliminate disk I/O lag during continuous drag gestures
        viewModelScope.launch {
            combine(panAzimuthOffset, panPitchOffset) { az, pitch -> Pair(az, pitch) }
                .drop(1)
                .debounce(300L)
                .collect { (az, pitch) ->
                    settingsStore.saveSunArAzimuthOffset(az)
                    settingsStore.saveSunArPitchOffset(pitch)
                }
        }

        // Update magnetic declination whenever location updates
        viewModelScope.launch {
            locationProvider.location.collect { loc ->
                orientationTracker.updateLocation(loc)
            }
        }

        // Ticker to update sun and moon positions every 2 seconds for selected date at current time-of-day
        viewModelScope.launch {
            while (isActive) {
                val date = selectedDateState.value
                val loc = locationProvider.location.value
                val now = Instant.now()
                val localTime = now.atZone(ZoneId.systemDefault()).toLocalTime()
                val targetInstant = date.atTime(localTime).atZone(ZoneId.systemDefault()).toInstant()
                currentSunPosState.value = SolarPositionCalculator.computePosition(targetInstant, loc)
                currentMoonPosState.value = LunarPositionCalculator.computePosition(targetInstant, loc)
                delay(2000L)
            }
        }
    }

    fun setCameraFov(fovDeg: Float) {
        if (kotlin.math.abs(baseCameraFovState.value - fovDeg) > 0.1f) {
            baseCameraFovState.value = fovDeg
        }
    }

    fun onPanGesture(deltaX: Float, deltaY: Float, viewWidth: Float, fovDeg: Float) {
        val factor = fovDeg / viewWidth.coerceAtLeast(1f)
        orientationTracker.onPanGesture(deltaX, deltaY, factor)
        panAzimuthOffset.value = orientationTracker.panAzimuthOffsetDeg
        panPitchOffset.value = orientationTracker.panPitchOffsetDeg
    }

    fun rotatePan(pitchRad: Float, yawRad: Float) {
        val deltaAzDeg = Math.toDegrees(yawRad.toDouble()).toFloat()
        val deltaElDeg = Math.toDegrees(pitchRad.toDouble()).toFloat()
        orientationTracker.onPanGesture(-deltaAzDeg, -deltaElDeg, 1f)
        panAzimuthOffset.value = orientationTracker.panAzimuthOffsetDeg
        panPitchOffset.value = orientationTracker.panPitchOffsetDeg
    }

    fun adjustPan(deltaAzimuthDeg: Float, deltaElevationDeg: Float) {
        orientationTracker.onPanGesture(-deltaAzimuthDeg, -deltaElevationDeg, 1f)
        panAzimuthOffset.value = orientationTracker.panAzimuthOffsetDeg
        panPitchOffset.value = orientationTracker.panPitchOffsetDeg
    }

    fun adjustAzimuthOffset(deltaDeg: Float) {
        adjustPan(deltaAzimuthDeg = deltaDeg, deltaElevationDeg = 0.0f)
    }

    fun adjustElevationOffset(deltaDeg: Float) {
        adjustPan(deltaAzimuthDeg = 0.0f, deltaElevationDeg = deltaDeg)
    }

    fun resetAzimuthOffset() {
        resetCalibration()
    }

    fun resumeSensorTracking() {
        orientationTracker.resumeSensorTracking()
        panAzimuthOffset.value = 0f
        panPitchOffset.value = 0f
        viewModelScope.launch {
            settingsStore.saveSunArAzimuthOffset(0f)
            settingsStore.saveSunArPitchOffset(0f)
        }
    }

    fun resetCalibration() {
        resumeSensorTracking()
    }

    fun adjustZoomBy(factor: Float) {
        val current = zoomRatioState.value
        val updated = (current * factor).coerceIn(0.5f, 4.0f)
        if (kotlin.math.abs(updated - current) > 0.001f) {
            zoomRatioState.value = updated
        }
    }

    fun setZoomRatio(ratio: Float) {
        zoomRatioState.value = ratio.coerceIn(0.5f, 4.0f)
    }

    fun resetZoom() {
        zoomRatioState.value = 1.0f
    }

    fun startSensors() {
        orientationTracker.start()
        locationProvider.startUpdates()
    }

    fun stopSensors() {
        orientationTracker.stop()
        locationProvider.stopUpdates()
    }

    fun refreshLocation() {
        locationProvider.refreshLocation()
    }

    fun nextDay() {
        selectedDateState.value = selectedDateState.value.plusDays(1)
    }

    fun prevDay() {
        selectedDateState.value = selectedDateState.value.minusDays(1)
    }

    fun setToday() {
        selectedDateState.value = LocalDate.now()
    }

    fun setDate(date: LocalDate) {
        selectedDateState.value = date
    }

    fun setBubbleInterval(interval: BubbleInterval) {
        bubbleIntervalState.value = interval
    }

    fun toggleKeepScreenOn() {
        setKeepScreenOn(!keepScreenOn.value)
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.saveSunArKeepScreenOn(enabled)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSensors()
    }
}
