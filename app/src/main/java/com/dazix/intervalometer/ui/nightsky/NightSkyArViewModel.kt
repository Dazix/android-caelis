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
import com.dazix.intervalometer.data.nightsky.Star
import com.dazix.intervalometer.domain.nightsky.CelestialBodyDetails
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
    val selectedBody: CelestialBodyDetails? = null,
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
        CameraFovCalculator.computeZoomedFov(baseFov, zoom).coerceIn(5.0f, 120.0f)
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

    private val userQuatState = MutableStateFlow(floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f))
    val isCalibrated: StateFlow<Boolean> = userQuatState.map { q ->
        !OrientationTracker.isIdentityQuaternion(q)
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

    private val selectedBodyState = MutableStateFlow<CelestialBodyDetails?>(null)
    val selectedBody: StateFlow<CelestialBodyDetails?> = selectedBodyState.asStateFlow()

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
        selectedBodyState,
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
        val selected = args[19] as CelestialBodyDetails?

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
            selectedBody = selected,
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

        // Initialize user rotation quaternion from settings store
        viewModelScope.launch {
            val initialQuat = settingsStore.nightSkyQuaternion.firstOrNull() ?: floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f)
            userQuatState.value = initialQuat
            orientationTracker.setUserQuaternion(initialQuat)
        }

        // Debounce saving rotation quaternion changes to eliminate disk I/O lag during continuous drag gestures
        viewModelScope.launch {
            userQuatState
                .drop(1)
                .debounce(300L)
                .collect { quat ->
                    settingsStore.saveNightSkyQuaternion(quat)
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

    /**
     * Resolves metadata for the tapped star from FlatBuffers catalog and updates [selectedBody].
     */
    fun selectStarByIndex(starIndex: Int) {
        val cat = catalogState.value ?: return
        val totalStars = cat.starsLength()
        if (starIndex !in 0 until totalStars) {
            selectedBodyState.value = null
            return
        }

        val star = Star()
        cat.stars(star, starIndex)

        // Query metadata vector for named star entry matching this index
        val metaLen = cat.metadataLength()
        val reusableMeta = com.dazix.intervalometer.data.nightsky.StarMetadata()
        var matchedName: String? = null
        var matchedType: String? = null
        var matchedConstellation: String? = null
        var matchedDistanceLy: Float = 0f

        for (m in 0 until metaLen) {
            cat.metadata(reusableMeta, m)
            if (reusableMeta.starIndex() == starIndex) {
                matchedName = reusableMeta.name()
                matchedType = reusableMeta.bodyType()
                matchedConstellation = reusableMeta.constellation()
                matchedDistanceLy = reusableMeta.distanceLy()
                break
            }
        }

        val app = getApplication<Application>()
        val name = matchedName ?: "HIP ${starIndex + 1}"
        val subtitle = matchedType ?: app.getString(R.string.celestial_star_subtitle)
        val distanceFormatted = if (matchedDistanceLy > 0f) {
            String.format(java.util.Locale.US, "%.1f ly", matchedDistanceLy)
        } else {
            "—"
        }

        val prop1 = matchedConstellation?.let {
            Pair(app.getString(R.string.celestial_constellation), it)
        }
        val prop2 = Pair(app.getString(R.string.celestial_distance), distanceFormatted)

        selectedBodyState.value = CelestialBodyDetails(
            name = name,
            subtitle = subtitle,
            property1 = prop1,
            property2 = prop2,
            magnitude = star.mag(),
            raDegrees = star.ra().toDouble(),
            decDegrees = star.dec().toDouble(),
        )
    }

    /**
     * Resolves astronomical metadata for the tapped constellation from FlatBuffers catalog and updates [selectedBody].
     */
    fun selectConstellationByIndex(constellationIndex: Int) {
        val cat = catalogState.value ?: return
        val totalConstellations = cat.constellationsLength()
        if (constellationIndex !in 0 until totalConstellations) {
            selectedBodyState.value = null
            return
        }

        val reusableConst = com.dazix.intervalometer.data.nightsky.Constellation()
        cat.constellations(reusableConst, constellationIndex)

        val app = getApplication<Application>()
        val name = reusableConst.localizedName() ?: reusableConst.name() ?: ""
        val subtitle = app.getString(R.string.celestial_constellation_subtitle)
        val brightestStar = reusableConst.brightestStar() ?: "—"
        val family = reusableConst.family() ?: "—"

        val prop1 = Pair(app.getString(R.string.celestial_brightest_star), brightestStar)
        val prop2 = Pair(app.getString(R.string.celestial_family), family)

        selectedBodyState.value = CelestialBodyDetails(
            name = name,
            subtitle = subtitle,
            property1 = prop1,
            property2 = prop2,
            magnitude = null,
            raDegrees = reusableConst.centroidRa().toDouble(),
            decDegrees = reusableConst.centroidDec().toDouble(),
        )
    }

    /**
     * Dismisses the celestial object selection.
     */
    fun clearSelectedBody() {
        selectedBodyState.value = null
    }

    fun rotatePan(pitchRad: Float, yawRad: Float) {
        orientationTracker.rotateUser(pitchRad, yawRad)
        userQuatState.value = orientationTracker.getUserQuaternion()
    }

    fun adjustPan(deltaAzimuthDeg: Float, deltaElevationDeg: Float) {
        val pitchRad = Math.toRadians(deltaElevationDeg.toDouble()).toFloat()
        val yawRad = Math.toRadians(deltaAzimuthDeg.toDouble()).toFloat()
        rotatePan(pitchRad = pitchRad, yawRad = yawRad)
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

    fun resetCalibration() {
        orientationTracker.resetUserRotation()
        userQuatState.value = floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f)
        viewModelScope.launch {
            settingsStore.saveNightSkyQuaternion(floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f))
        }
    }

    fun adjustZoomBy(factor: Float) {
        val current = zoomRatioState.value
        val updated = (current * factor).coerceIn(1.0f, 10.0f)
        if (kotlin.math.abs(updated - current) > 0.001f) {
            zoomRatioState.value = updated
        }
    }

    fun setZoomRatio(ratio: Float) {
        zoomRatioState.value = ratio.coerceIn(1.0f, 10.0f)
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
