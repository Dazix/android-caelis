package com.dazix.intervalometer.ui.nightsky

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dazix.intervalometer.R
import com.dazix.intervalometer.ar.CameraFovCalculator
import com.dazix.intervalometer.ar.DeviceOrientation
import com.dazix.intervalometer.ar.OrientationTracker
import com.dazix.intervalometer.ar.SolarLocationProvider
import com.dazix.intervalometer.data.SettingsStore
import com.dazix.intervalometer.data.nightsky.NightSkyCatalog
import com.dazix.intervalometer.domain.nightsky.NightSkyCatalogLoader
import com.dazix.intervalometer.domain.nightsky.SiderealTime
import com.dazix.intervalometer.domain.solar.GeoLocation
import com.dazix.intervalometer.domain.solar.SolarPositionCalculator
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

data class NightSkyArUiState(
    val location: GeoLocation = GeoLocation.DEFAULT,
    val hasGpsFix: Boolean = false,
    val keepScreenOn: Boolean = false,
    val redVisionMode: Boolean = true,
    val isNight: Boolean = true,
    val isRedVisionOverridden: Boolean = false,
    val isCalibrated: Boolean = false,
    val cameraFovHorizontal: Float = CameraFovCalculator.DEFAULT_PORTRAIT_FOV_DEGREES,
    val zoomRatio: Float = 1.0f,
    val lstDegrees: Double = 0.0,
    val lstFormatted: String = "00h 00m 00s",
    val visibleStarCount: Int = 0,
    val totalStarCount: Int = 0,
    val constellationsCount: Int = 0,
    val isSkyBackgroundOpaque: Boolean = false,
    val isMilkyWayVisible: Boolean = true,
    val isAtmosphereVisible: Boolean = true,
    val isLandscapeVisible: Boolean = true,
    val isGridVisible: Boolean = true,
    val isConstellationsVisible: Boolean = true,
)

@OptIn(FlowPreview::class)
class NightSkyArViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val orientationTracker = OrientationTracker(application)
    private val locationProvider = SolarLocationProvider(application)

    private val catalogState = MutableStateFlow<NightSkyCatalog?>(null)
    val catalog: StateFlow<NightSkyCatalog?> = catalogState.asStateFlow()

    private val baseCameraFovState = MutableStateFlow(CameraFovCalculator.DEFAULT_PORTRAIT_FOV_DEGREES)
    private val zoomRatioState = MutableStateFlow(1.0f)
    val zoomRatio: StateFlow<Float> = zoomRatioState.asStateFlow()

    // Dynamic effective FOV factoring in pinch-to-zoom
    val cameraFovHorizontal: StateFlow<Float> = combine(baseCameraFovState, zoomRatioState) { baseFov, zoom ->
        (baseFov / zoom).coerceIn(8.0f, 120.0f)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CameraFovCalculator.DEFAULT_PORTRAIT_FOV_DEGREES)

    private val lstDegreesState = MutableStateFlow(0.0)
    val lstDegrees: StateFlow<Double> = lstDegreesState.asStateFlow()

    private val lstFormattedState = MutableStateFlow("00h 00m 00s")
    val lstFormatted: StateFlow<String> = lstFormattedState.asStateFlow()

    private val visibleStarCountState = MutableStateFlow(0)
    val visibleStarCount: StateFlow<Int> = visibleStarCountState.asStateFlow()

    private val isNightState = MutableStateFlow(SolarPositionCalculator.isNight(Instant.now(), GeoLocation.DEFAULT))
    val isNight: StateFlow<Boolean> = isNightState.asStateFlow()

    private val manualRedVisionOverride = MutableStateFlow<Boolean?>(null)
    val isRedVisionOverridden: StateFlow<Boolean> = manualRedVisionOverride.combine(isNightState) { override, _ ->
        override != null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val redVisionMode: StateFlow<Boolean> = combine(isNightState, manualRedVisionOverride) { night, override ->
        override ?: night
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val orientation: StateFlow<DeviceOrientation?> = orientationTracker.orientation
    val location: StateFlow<GeoLocation> = locationProvider.location
    val hasGpsFix: StateFlow<Boolean> = locationProvider.hasGpsFix

    val keepScreenOn: StateFlow<Boolean> = settingsStore.nightSkyKeepScreenOn.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        false,
    )

    private val userOffsetsState = MutableStateFlow(Pair(0.0f, 0.0f))
    val isCalibrated: StateFlow<Boolean> = userOffsetsState.map { (az, pitch) ->
        kotlin.math.abs(az) > 0.01f || kotlin.math.abs(pitch) > 0.01f
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isSkyBackgroundOpaque: StateFlow<Boolean> = settingsStore.nightSkyOpaqueBackground.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        false,
    )

    val isMilkyWayVisible: StateFlow<Boolean> = settingsStore.nightSkyMilkyWayVisible.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        true,
    )

    val isAtmosphereVisible: StateFlow<Boolean> = settingsStore.nightSkyAtmosphereVisible.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        true,
    )

    val isLandscapeVisible: StateFlow<Boolean> = settingsStore.nightSkyLandscapeVisible.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        true,
    )

    val isGridVisible: StateFlow<Boolean> = settingsStore.nightSkyGridVisible.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        true,
    )

    val isConstellationsVisible: StateFlow<Boolean> = settingsStore.nightSkyConstellationsVisible.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        true,
    )

    val uiState: StateFlow<NightSkyArUiState> = combine(
        locationProvider.location,
        locationProvider.hasGpsFix,
        keepScreenOn,
        redVisionMode,
        isNightState,
        manualRedVisionOverride,
        isCalibrated,
        cameraFovHorizontal,
        zoomRatioState,
        lstDegreesState,
        lstFormattedState,
        visibleStarCountState,
        catalogState,
        isSkyBackgroundOpaque,
        isMilkyWayVisible,
        isAtmosphereVisible,
        isLandscapeVisible,
        isGridVisible,
        isConstellationsVisible,
    ) { args: Array<Any?> ->
        val loc = args[0] as GeoLocation
        val gps = args[1] as Boolean
        val keepOn = args[2] as Boolean
        val redMode = args[3] as Boolean
        val night = args[4] as Boolean
        val redOverride = args[5] as Boolean?
        val calib = args[6] as Boolean
        val fov = args[7] as Float
        val zoom = args[8] as Float
        val lstDeg = args[9] as Double
        val lstFmt = args[10] as String
        val visibleStars = args[11] as Int
        val cat = args[12] as NightSkyCatalog?
        val opaque = args[13] as Boolean
        val milkyWay = args[14] as Boolean
        val atmosphere = args[15] as Boolean
        val landscape = args[16] as Boolean
        val grid = args[17] as Boolean
        val constellations = args[18] as Boolean

        NightSkyArUiState(
            location = loc,
            hasGpsFix = gps,
            keepScreenOn = keepOn,
            redVisionMode = redMode,
            isNight = night,
            isRedVisionOverridden = redOverride != null,
            isCalibrated = calib,
            cameraFovHorizontal = fov,
            zoomRatio = zoom,
            lstDegrees = lstDeg,
            lstFormatted = lstFmt,
            visibleStarCount = visibleStars,
            totalStarCount = cat?.starsLength() ?: 0,
            constellationsCount = cat?.constellationsLength() ?: 0,
            isSkyBackgroundOpaque = opaque,
            isMilkyWayVisible = milkyWay,
            isAtmosphereVisible = atmosphere,
            isLandscapeVisible = landscape,
            isGridVisible = grid,
            isConstellationsVisible = constellations,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NightSkyArUiState())

    init {
        // Load FlatBuffers Star Catalog binary asynchronously from res/raw/catalog.bin
        viewModelScope.launch {
            val cat = withContext(Dispatchers.IO) {
                try {
                    application.resources.openRawResource(R.raw.catalog).use { stream ->
                        NightSkyCatalogLoader.loadFromStream(stream)
                    }
                } catch (_: Exception) {
                    null
                }
            }
            catalogState.value = cat
        }

        // Initialize user spherical offsets from settings store
        viewModelScope.launch {
            val initialAz = settingsStore.nightSkyAzimuthOffset.firstOrNull() ?: 0.0f
            val initialPitch = settingsStore.nightSkyPitchOffset.firstOrNull() ?: 0.0f
            userOffsetsState.value = Pair(initialAz, initialPitch)
            orientationTracker.setUserOffsets(initialAz, initialPitch)
        }

        // Debounce saving rotation offset changes to eliminate disk I/O lag during continuous drag gestures
        viewModelScope.launch {
            userOffsetsState
                .drop(1)
                .debounce(300L)
                .collect { (az, pitch) ->
                    settingsStore.saveNightSkyAzimuthOffset(az)
                    settingsStore.saveNightSkyPitchOffset(pitch)
                }
        }

        // Update magnetic declination on location updates
        viewModelScope.launch {
            locationProvider.location.collect { loc ->
                orientationTracker.updateLocation(loc)
                isNightState.value = SolarPositionCalculator.isNight(Instant.now(), loc)
            }
        }

        // Sidereal time & day/night ticker updating every second
        viewModelScope.launch {
            while (isActive) {
                val loc = locationProvider.location.value
                val now = Instant.now()
                val lstDeg = SiderealTime.computeLstDegrees(now, loc.longitude)
                val lstHours = lstDeg / 15.0
                lstDegreesState.value = lstDeg
                lstFormattedState.value = SiderealTime.formatLstHours(lstHours)
                isNightState.value = SolarPositionCalculator.isNight(now, loc)
                delay(1000L)
            }
        }
    }

    fun setCameraFov(fovDeg: Float) {
        if (kotlin.math.abs(baseCameraFovState.value - fovDeg) > 0.1f) {
            baseCameraFovState.value = fovDeg
        }
    }

    fun updateVisibleStarCount(count: Int) {
        if (visibleStarCountState.value != count) {
            visibleStarCountState.value = count
        }
    }

    fun rotatePan(deltaAzDeg: Float, deltaPitchDeg: Float) {
        orientationTracker.rotateUser(deltaAzDeg, deltaPitchDeg)
        userOffsetsState.value = orientationTracker.getUserOffsets()
    }

    fun adjustPan(deltaAzimuthDeg: Float, deltaElevationDeg: Float) {
        rotatePan(deltaAzDeg = deltaAzimuthDeg, deltaPitchDeg = deltaElevationDeg)
    }

    fun adjustAzimuthOffset(deltaDeg: Float) {
        rotatePan(deltaAzDeg = deltaDeg, deltaPitchDeg = 0.0f)
    }

    fun adjustElevationOffset(deltaDeg: Float) {
        rotatePan(deltaAzDeg = 0.0f, deltaPitchDeg = deltaDeg)
    }

    fun resetAzimuthOffset() {
        resetCalibration()
    }

    fun resetCalibration() {
        orientationTracker.resetUserRotation()
        userOffsetsState.value = Pair(0.0f, 0.0f)
        viewModelScope.launch {
            settingsStore.saveNightSkyAzimuthOffset(0.0f)
            settingsStore.saveNightSkyPitchOffset(0.0f)
        }
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

    fun toggleRedVisionMode() {
        val currentActive = manualRedVisionOverride.value ?: isNightState.value
        manualRedVisionOverride.value = !currentActive
    }

    fun setRedVisionMode(enabled: Boolean) {
        manualRedVisionOverride.value = enabled
    }

    fun resetRedVisionToAuto() {
        manualRedVisionOverride.value = null
    }

    fun toggleKeepScreenOn() {
        setKeepScreenOn(!keepScreenOn.value)
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.saveNightSkyKeepScreenOn(enabled)
        }
    }

    fun toggleSkyBackgroundOpaque() {
        setSkyBackgroundOpaque(!isSkyBackgroundOpaque.value)
    }

    fun setSkyBackgroundOpaque(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.saveNightSkyOpaqueBackground(enabled)
        }
    }

    fun toggleMilkyWayVisible() {
        setMilkyWayVisible(!isMilkyWayVisible.value)
    }

    fun setMilkyWayVisible(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.saveNightSkyMilkyWayVisible(enabled)
        }
    }

    fun toggleAtmosphereVisible() {
        setAtmosphereVisible(!isAtmosphereVisible.value)
    }

    fun setAtmosphereVisible(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.saveNightSkyAtmosphereVisible(enabled)
        }
    }

    fun toggleLandscapeVisible() {
        setLandscapeVisible(!isLandscapeVisible.value)
    }

    fun setLandscapeVisible(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.saveNightSkyLandscapeVisible(enabled)
        }
    }

    fun toggleGridVisible() {
        setGridVisible(!isGridVisible.value)
    }

    fun setGridVisible(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.saveNightSkyGridVisible(enabled)
        }
    }

    fun toggleConstellationsVisible() {
        setConstellationsVisible(!isConstellationsVisible.value)
    }

    fun setConstellationsVisible(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.saveNightSkyConstellationsVisible(enabled)
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

    override fun onCleared() {
        super.onCleared()
        stopSensors()
    }
}
