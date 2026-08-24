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
import com.dazix.intervalometer.domain.nightsky.CelestialMatrixCalculator
import com.dazix.intervalometer.domain.nightsky.CelestialSearchIndex
import com.dazix.intervalometer.domain.nightsky.CelestialType
import com.dazix.intervalometer.domain.nightsky.EquatorialCoordinates
import com.dazix.intervalometer.domain.nightsky.NightSkyCatalogLoader
import com.dazix.intervalometer.domain.nightsky.NightVisionActivationMode
import com.dazix.intervalometer.domain.nightsky.NightVisionState
import com.dazix.intervalometer.domain.nightsky.NightVisionTheme
import com.dazix.intervalometer.domain.nightsky.SearchResultItem
import com.dazix.intervalometer.domain.nightsky.SiderealTime
import com.dazix.intervalometer.domain.nightsky.isTimeInRange
import com.dazix.intervalometer.domain.solar.GeoLocation
import com.dazix.intervalometer.domain.solar.SolarPositionCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalTime

data class NightSkyArUiState(
    val location: GeoLocation = GeoLocation.DEFAULT,
    val hasGpsFix: Boolean = false,
    val keepScreenOn: Boolean = false,
    val nightVisionState: NightVisionState = NightVisionState(),
    val redVisionMode: Boolean = nightVisionState.isActive,
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
    val isConstellationArtVisible: Boolean = true,
    val isSensorTrackingEnabled: Boolean = true,
    val searchQuery: String = "",
    val searchResults: List<SearchResultItem> = emptyList(),
    val isSearchActive: Boolean = false,
    val selectedStarIndex: Int = -1,
    val selectedConstellationIndex: Int = -1,
    val selectedBody: CelestialBodyDetails? = null,
    val debugArtOffsetX: Float = 0f,
    val debugArtOffsetY: Float = 0f,
    val debugArtScaleMultiplier: Float = 1.0f,
    val debugArtRotationOffset: Float = 0f,
    val isDebugCalibrationVisible: Boolean = false,
)

@OptIn(FlowPreview::class)
class NightSkyArViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val orientationTracker = OrientationTracker(application)
    private val locationProvider = SolarLocationProvider(application)

    private val catalogState = MutableStateFlow<NightSkyCatalog?>(null)
    val catalog: StateFlow<NightSkyCatalog?> = catalogState.asStateFlow()

    private var searchIndex: List<SearchResultItem> = emptyList()

    val searchQuery = MutableStateFlow("")
    private val _searchResults = MutableStateFlow<List<SearchResultItem>>(emptyList())
    val searchResults: StateFlow<List<SearchResultItem>> = _searchResults.asStateFlow()

    val isSearchActive = MutableStateFlow(false)

    val selectedStarIndex = MutableStateFlow(-1)
    val selectedConstellationIndex = MutableStateFlow(-1)

    val isSensorTrackingEnabled: StateFlow<Boolean> = settingsStore.nightSkySensorTracking.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        true,
    )

    val manualPanRa = MutableStateFlow(0f)
    val manualPanDec = MutableStateFlow(0f)
    private val manualMatrixBuffer = FloatArray(16)
    private val manualOrientationState = MutableStateFlow<DeviceOrientation?>(null)

    private var cameraAnimationJob: Job? = null

    private val baseCameraFovState = MutableStateFlow(CameraFovCalculator.DEFAULT_PORTRAIT_FOV_DEGREES)
    private val zoomRatioState = MutableStateFlow(1.0f)
    val zoomRatio: StateFlow<Float> = zoomRatioState.asStateFlow()

    // Dynamic effective FOV factoring in pinch-to-zoom
    val cameraFovHorizontal: StateFlow<Float> = combine(baseCameraFovState, zoomRatioState) { baseFov, zoom ->
        CameraFovCalculator.computeZoomedFov(baseFov, zoom).coerceIn(5.0f, 140.0f)
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

    private val currentTimeState = MutableStateFlow(LocalTime.now())

    val nightVisionTheme: StateFlow<NightVisionTheme> = settingsStore.nightVisionTheme.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        NightVisionTheme.RED,
    )

    val nightVisionActivationMode: StateFlow<NightVisionActivationMode> = settingsStore.nightVisionActivationMode.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        NightVisionActivationMode.AUTO_SOLAR,
    )

    val customStartTime: StateFlow<LocalTime> = settingsStore.customStartTime.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        LocalTime.of(22, 0),
    )

    val customEndTime: StateFlow<LocalTime> = settingsStore.customEndTime.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        LocalTime.of(6, 0),
    )

    val nightVisionState: StateFlow<NightVisionState> = combine(
        nightVisionActivationMode,
        nightVisionTheme,
        customStartTime,
        customEndTime,
        isNightState,
        currentTimeState,
        manualRedVisionOverride,
    ) { args: Array<Any?> ->
        val mode = args[0] as NightVisionActivationMode
        val theme = args[1] as NightVisionTheme
        val start = args[2] as LocalTime
        val end = args[3] as LocalTime
        val isSolarNight = args[4] as Boolean
        val currentTime = args[5] as LocalTime
        val override = args[6] as? Boolean

        val isActive = override ?: when (mode) {
            NightVisionActivationMode.OFF -> false
            NightVisionActivationMode.AUTO_SOLAR -> isSolarNight
            NightVisionActivationMode.CUSTOM_TIME -> isTimeInRange(currentTime, start, end)
        }
        NightVisionState(isActive = isActive, theme = theme)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NightVisionState())

    val redVisionMode: StateFlow<Boolean> = nightVisionState.map { it.isActive }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        true,
    )

    val orientation: StateFlow<DeviceOrientation?> = combine(
        isSensorTrackingEnabled,
        orientationTracker.orientation,
        manualOrientationState,
    ) { sensorEnabled, sensorOri, manualOri ->
        if (sensorEnabled) sensorOri else manualOri
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

    val isConstellationArtVisible: StateFlow<Boolean> = settingsStore.nightSkyConstellationArtVisible.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        true,
    )

    // In-App Calibration Debugger StateFlows
    val debugArtOffsetX = MutableStateFlow(0f)
    val debugArtOffsetY = MutableStateFlow(0f)
    val debugArtScaleMultiplier = MutableStateFlow(1.0f)
    val debugArtRotationOffset = MutableStateFlow(0f)
    val isDebugCalibrationVisible = MutableStateFlow(false)

    private val selectedBodyState = MutableStateFlow<CelestialBodyDetails?>(null)
    val selectedBody: StateFlow<CelestialBodyDetails?> = selectedBodyState.asStateFlow()

    val uiState: StateFlow<NightSkyArUiState> = combine(
        listOf(
            locationProvider.location,
            locationProvider.hasGpsFix,
            keepScreenOn,
            nightVisionState,
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
            isConstellationArtVisible,
            selectedBodyState,
            isSensorTrackingEnabled,
            searchQuery,
            searchResults,
            isSearchActive,
            selectedStarIndex,
            selectedConstellationIndex,
            debugArtOffsetX,
            debugArtOffsetY,
            debugArtScaleMultiplier,
            debugArtRotationOffset,
            isDebugCalibrationVisible,
        )
    ) { args: Array<Any?> ->
        val loc = args[0] as GeoLocation
        val gps = args[1] as Boolean
        val keepOn = args[2] as Boolean
        val nvState = args[3] as NightVisionState
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
        val constellationArt = args[19] as Boolean
        val selected = args[20] as CelestialBodyDetails?
        val sensorTracking = args[21] as Boolean
        val query = args[22] as String
        @Suppress("UNCHECKED_CAST")
        val results = args[23] as List<SearchResultItem>
        val searchActive = args[24] as Boolean
        val starIdx = args[25] as Int
        val constIdx = args[26] as Int
        val dbgOffsetX = args[27] as Float
        val dbgOffsetY = args[28] as Float
        val dbgScale = args[29] as Float
        val dbgRot = args[30] as Float
        val dbgVis = args[31] as Boolean

        NightSkyArUiState(
            location = loc,
            hasGpsFix = gps,
            keepScreenOn = keepOn,
            nightVisionState = nvState,
            redVisionMode = nvState.isActive,
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
            isConstellationArtVisible = constellationArt,
            isSensorTrackingEnabled = sensorTracking,
            searchQuery = query,
            searchResults = results,
            isSearchActive = searchActive,
            selectedStarIndex = starIdx,
            selectedConstellationIndex = constIdx,
            selectedBody = selected,
            debugArtOffsetX = dbgOffsetX,
            debugArtOffsetY = dbgOffsetY,
            debugArtScaleMultiplier = dbgScale,
            debugArtRotationOffset = dbgRot,
            isDebugCalibrationVisible = dbgVis,
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
            if (cat != null) {
                searchIndex = CelestialSearchIndex.buildSearchIndex(cat)
            }
        }

        // Search autocomplete query debounce collector
        viewModelScope.launch {
            searchQuery
                .debounce(300L)
                .distinctUntilChanged()
                .collect { query ->
                    _searchResults.value = CelestialSearchIndex.filter(searchIndex, query, limit = 15)
                }
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
                if (!isSensorTrackingEnabled.value) {
                    updateManualOrientation()
                }
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
                currentTimeState.value = LocalTime.now()
                if (!isSensorTrackingEnabled.value) {
                    updateManualOrientation()
                }
                delay(1000L)
            }
        }
    }

    private fun updateManualOrientation() {
        val ra = manualPanRa.value.toDouble()
        val dec = manualPanDec.value.toDouble()
        val lst = lstDegreesState.value
        val lat = locationProvider.location.value.latitude

        val (az, el) = EquatorialCoordinates.equatorialToHorizontal(ra, dec, lst, lat)
        CelestialMatrixCalculator.computeManualRotationMatrix(az, el, manualMatrixBuffer)

        manualOrientationState.value = DeviceOrientation(
            rotationMatrix = manualMatrixBuffer.clone(),
            cameraAzimuthDegrees = az.toFloat(),
            cameraElevationDegrees = el.toFloat(),
            cameraRollDegrees = 0f,
            accuracy = 3,
        )
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
            selectedStarIndex.value = -1
            selectedBodyState.value = null
            return
        }

        selectedStarIndex.value = starIndex
        selectedConstellationIndex.value = -1

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
            selectedConstellationIndex.value = -1
            selectedBodyState.value = null
            return
        }

        selectedConstellationIndex.value = constellationIndex
        selectedStarIndex.value = -1

        val reusableConst = com.dazix.intervalometer.data.nightsky.Constellation()
        cat.constellations(reusableConst, constellationIndex)

        val app = getApplication<Application>()
        val name = reusableConst.localizedName() ?: reusableConst.name() ?: ""
        val isAsterism = reusableConst.isAsterism()
        val subtitle = if (isAsterism) {
            app.getString(R.string.celestial_asterism_subtitle)
        } else {
            app.getString(R.string.celestial_constellation_subtitle)
        }
        val brightestStar = reusableConst.brightestStar() ?: "—"
        val family = reusableConst.family() ?: "—"

        val prop1 = Pair(app.getString(R.string.celestial_brightest_star), brightestStar)
        val prop2 = if (isAsterism) {
            Pair(app.getString(R.string.celestial_parent_constellation), family)
        } else {
            Pair(app.getString(R.string.celestial_family), family)
        }

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
     * Selects a celestial body from search results, updates selection state,
     * and in Manual Mode smoothly pans the virtual camera to the target's coordinates.
     */
    fun selectCelestialBody(item: SearchResultItem) {
        if (item.type == CelestialType.STAR) {
            selectStarByIndex(item.indexId)
        } else {
            selectConstellationByIndex(item.indexId)
        }
        isSearchActive.value = false
        searchQuery.value = ""

        if (!isSensorTrackingEnabled.value) {
            // Manual Mode: Smoothly pan virtual camera to target
            animateCameraToCoordinates(targetRa = item.ra, targetDec = item.dec)
        }
    }

    /**
     * Smoothly animates the virtual camera to [targetRa] and [targetDec] over 800ms
     * using the shortest spherical angular path.
     */
    fun animateCameraToCoordinates(targetRa: Float, targetDec: Float) {
        cameraAnimationJob?.cancel()
        cameraAnimationJob = viewModelScope.launch {
            val currentRa = manualPanRa.value
            val currentDec = manualPanDec.value

            val raDiff = CelestialSearchIndex.getShortestAngularDistance(currentRa, targetRa)
            val startRa = currentRa
            val endRa = currentRa + raDiff
            val startDec = currentDec
            val endDec = targetDec.coerceIn(-89.9f, 89.9f)

            val durationMs = 800L
            val startTime = System.currentTimeMillis()

            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                val fraction = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                // FastOutSlowIn easing curve: 3*t^2 - 2*t^3 (smooth cubic S-curve)
                val t = fraction * fraction * (3f - 2f * fraction)

                val interpolatedRa = startRa + (endRa - startRa) * t
                val interpolatedDec = startDec + (endDec - startDec) * t

                manualPanRa.value = (interpolatedRa % 360f + 360f) % 360f
                manualPanDec.value = interpolatedDec
                updateManualOrientation()

                if (fraction >= 1.0f) break
                delay(16L)
            }
        }
    }

    /**
     * Dismisses the celestial object selection.
     */
    fun clearSelectedBody() {
        selectedStarIndex.value = -1
        selectedConstellationIndex.value = -1
        selectedBodyState.value = null
    }

    fun rotatePan(pitchRad: Float, yawRad: Float, rollRad: Float = 0.0f) {
        if (isSensorTrackingEnabled.value) {
            orientationTracker.rotateUser(pitchRad, yawRad, rollRad)
            userQuatState.value = orientationTracker.getUserQuaternion()
        } else {
            val deltaAzDeg = Math.toDegrees(yawRad.toDouble()).toFloat()
            val deltaElDeg = Math.toDegrees(pitchRad.toDouble()).toFloat()

            val lst = lstDegreesState.value
            val lat = locationProvider.location.value.latitude
            val (currentAz, currentEl) = EquatorialCoordinates.equatorialToHorizontal(
                manualPanRa.value.toDouble(),
                manualPanDec.value.toDouble(),
                lst,
                lat,
            )

            val newAz = (currentAz - deltaAzDeg + 360.0) % 360.0
            val newEl = (currentEl + deltaElDeg).coerceIn(-89.9, 89.9)

            val (newRa, newDec) = EquatorialCoordinates.horizontalToEquatorial(newAz, newEl, lst, lat)
            manualPanRa.value = newRa.toFloat()
            manualPanDec.value = newDec.toFloat()
            updateManualOrientation()
        }
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

    fun toggleSensorTracking() {
        setSensorTrackingEnabled(!isSensorTrackingEnabled.value)
    }

    fun setSensorTrackingEnabled(enabled: Boolean) {
        if (!enabled && isSensorTrackingEnabled.value) {
            // Switching from Sensor -> Manual: capture current pointing direction as manual starting point
            val currentOrientation = orientationTracker.orientation.value
            if (currentOrientation != null) {
                val lst = lstDegreesState.value
                val lat = locationProvider.location.value.latitude
                val az = currentOrientation.cameraAzimuthDegrees.toDouble()
                val el = currentOrientation.cameraElevationDegrees.toDouble()
                val (ra, dec) = EquatorialCoordinates.horizontalToEquatorial(az, el, lst, lat)
                manualPanRa.value = ra.toFloat()
                manualPanDec.value = dec.toFloat()
                updateManualOrientation()
            }
            orientationTracker.stop()
        } else if (enabled && !isSensorTrackingEnabled.value) {
            orientationTracker.start()
        }
        viewModelScope.launch {
            settingsStore.saveNightSkySensorTracking(enabled)
        }
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setSearchActive(active: Boolean) {
        isSearchActive.value = active
        if (!active) {
            searchQuery.value = ""
        }
    }

    fun adjustZoomBy(factor: Float) {
        val current = zoomRatioState.value
        val updated = (current * factor).coerceIn(0.4f, 10.0f)
        if (kotlin.math.abs(updated - current) > 0.001f) {
            zoomRatioState.value = updated
        }
    }

    fun setZoomRatio(ratio: Float) {
        zoomRatioState.value = ratio.coerceIn(0.4f, 10.0f)
    }

    fun resetZoom() {
        zoomRatioState.value = 1.0f
    }

    fun toggleRedVisionMode() {
        val currentActive = nightVisionState.value.isActive
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

    fun toggleConstellationArtVisible() {
        setConstellationArtVisible(!isConstellationArtVisible.value)
    }

    fun setConstellationArtVisible(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.saveNightSkyConstellationArtVisible(enabled)
        }
    }

    // In-App Calibration Debugger controls
    fun setDebugArtOffsetX(v: Float) {
        debugArtOffsetX.value = v
    }

    fun setDebugArtOffsetY(v: Float) {
        debugArtOffsetY.value = v
    }

    fun setDebugArtScaleMultiplier(v: Float) {
        debugArtScaleMultiplier.value = v
    }

    fun setDebugArtRotationOffset(v: Float) {
        debugArtRotationOffset.value = v
    }

    fun toggleDebugCalibration() {
        isDebugCalibrationVisible.value = !isDebugCalibrationVisible.value
    }

    fun setDebugCalibrationVisible(visible: Boolean) {
        isDebugCalibrationVisible.value = visible
    }

    fun resetDebugCalibration() {
        debugArtOffsetX.value = 0f
        debugArtOffsetY.value = 0f
        debugArtScaleMultiplier.value = 1.0f
        debugArtRotationOffset.value = 0f
    }

    fun startSensors() {
        if (isSensorTrackingEnabled.value) {
            orientationTracker.start()
        }
        locationProvider.startUpdates()
        if (!isSensorTrackingEnabled.value) {
            updateManualOrientation()
        }
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
        cameraAnimationJob?.cancel()
        stopSensors()
    }
}
