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
import com.dazix.intervalometer.domain.solar.SolarCoordinates
import com.dazix.intervalometer.domain.solar.SolarPositionCalculator
import com.dazix.intervalometer.domain.solar.SolarTrajectory
import com.dazix.intervalometer.domain.solar.SolarTrajectoryPlanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    val keepScreenOn: Boolean = false,
    val azimuthOffsetDegrees: Float = 0.0f,
    val cameraFovHorizontal: Float = CameraFovCalculator.DEFAULT_PORTRAIT_FOV_DEGREES,
    val trajectory: SolarTrajectory = SolarTrajectoryPlanner.planTrajectory(
        LocalDate.now(),
        GeoLocation.DEFAULT,
        BubbleInterval.ONE_HOUR,
    ),
    val currentSunPosition: SolarCoordinates = SolarPositionCalculator.computePosition(
        Instant.now(),
        GeoLocation.DEFAULT,
    ),
)

class SunArViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val orientationTracker = OrientationTracker(application)
    private val locationProvider = SolarLocationProvider(application)

    private val selectedDateState = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = selectedDateState.asStateFlow()

    private val bubbleIntervalState = MutableStateFlow(BubbleInterval.ONE_HOUR)
    val bubbleInterval: StateFlow<BubbleInterval> = bubbleIntervalState.asStateFlow()

    private val cameraFovState = MutableStateFlow(CameraFovCalculator.DEFAULT_PORTRAIT_FOV_DEGREES)
    val cameraFovHorizontal: StateFlow<Float> = cameraFovState.asStateFlow()

    val orientation: StateFlow<DeviceOrientation?> = orientationTracker.orientation
    val location: StateFlow<GeoLocation> = locationProvider.location
    val hasGpsFix: StateFlow<Boolean> = locationProvider.hasGpsFix

    val keepScreenOn: StateFlow<Boolean> = settingsStore.sunArKeepScreenOn.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        false,
    )

    val azimuthOffsetDegrees: StateFlow<Float> = settingsStore.sunArAzimuthOffset.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        0.0f,
    )

    private val currentSunPosState = MutableStateFlow(
        SolarPositionCalculator.computePosition(Instant.now(), locationProvider.location.value)
    )
    val currentSunPosition: StateFlow<SolarCoordinates> = currentSunPosState.asStateFlow()

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

    val uiState: StateFlow<SunArUiState> = combine(
        selectedDateState,
        bubbleIntervalState,
        locationProvider.location,
        locationProvider.hasGpsFix,
        trajectory,
        currentSunPosState,
        keepScreenOn,
        azimuthOffsetDegrees,
        cameraFovState,
    ) { args: Array<Any?> ->
        val date = args[0] as LocalDate
        val interval = args[1] as BubbleInterval
        val loc = args[2] as GeoLocation
        val gps = args[3] as Boolean
        val traj = args[4] as SolarTrajectory
        val sunPos = args[5] as SolarCoordinates
        val keepOn = args[6] as Boolean
        val azOffset = args[7] as Float
        val fov = args[8] as Float
        SunArUiState(
            selectedDate = date,
            isToday = date == LocalDate.now(),
            bubbleInterval = interval,
            location = loc,
            hasGpsFix = gps,
            keepScreenOn = keepOn,
            azimuthOffsetDegrees = azOffset,
            cameraFovHorizontal = fov,
            trajectory = traj,
            currentSunPosition = sunPos,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SunArUiState())

    init {
        // Update magnetic declination whenever location updates
        viewModelScope.launch {
            locationProvider.location.collect { loc ->
                orientationTracker.updateLocation(loc)
            }
        }

        // Apply azimuth calibration offset to orientation tracker
        viewModelScope.launch {
            azimuthOffsetDegrees.collect { offset ->
                orientationTracker.updateAzimuthOffset(offset)
            }
        }

        // Ticker to update sun position every 2 seconds for selected date at current time-of-day
        viewModelScope.launch {
            while (isActive) {
                val date = selectedDateState.value
                val loc = locationProvider.location.value
                val now = Instant.now()
                val localTime = now.atZone(ZoneId.systemDefault()).toLocalTime()
                val targetInstant = date.atTime(localTime).atZone(ZoneId.systemDefault()).toInstant()
                currentSunPosState.value = SolarPositionCalculator.computePosition(targetInstant, loc)
                delay(2000L)
            }
        }
    }

    fun setCameraFov(fovDeg: Float) {
        if (kotlin.math.abs(cameraFovState.value - fovDeg) > 0.1f) {
            cameraFovState.value = fovDeg
        }
    }

    fun adjustAzimuthOffset(deltaDeg: Float) {
        val current = azimuthOffsetDegrees.value
        var updated = current + deltaDeg
        // Keep within [-180°, 180°]
        while (updated > 180f) updated -= 360f
        while (updated < -180f) updated += 360f
        viewModelScope.launch {
            settingsStore.saveSunArAzimuthOffset(updated)
        }
    }

    fun resetAzimuthOffset() {
        viewModelScope.launch {
            settingsStore.saveSunArAzimuthOffset(0.0f)
        }
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

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.saveSunArKeepScreenOn(enabled)
        }
    }

    fun toggleKeepScreenOn() {
        setKeepScreenOn(!keepScreenOn.value)
    }

    override fun onCleared() {
        super.onCleared()
        stopSensors()
    }
}
