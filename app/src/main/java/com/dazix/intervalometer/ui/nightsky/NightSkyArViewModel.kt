package com.dazix.intervalometer.ui.nightsky

import android.app.Application
import android.view.Surface
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
import com.dazix.intervalometer.domain.nightsky.PlanetState
import com.dazix.intervalometer.domain.nightsky.PlanetaryEphemerisCalculator
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
    val isPlanetsVisible: Boolean = true,
    val isConstellationsVisible: Boolean = true,
    val isConstellationArtVisible: Boolean = true,
    val isSensorTrackingEnabled: Boolean = true,
    val searchQuery: String = "",
    val searchResults: List<SearchResultItem> = emptyList(),
    val searchHistory: List<SearchResultItem> = emptyList(),
    val isSearchActive: Boolean = false,
    val selectedStarIndex: Int = -1,
    val selectedConstellationIndex: Int = -1,
    val selectedPlanetId: String? = null,
    val selectedBody: CelestialBodyDetails? = null,
    val planetStates: List<com.dazix.intervalometer.domain.nightsky.PlanetState> = emptyList(),
    val debugArtOffsetX: Float = 0f,
    val debugArtOffsetY: Float = 0f,
    val debugArtScaleMultiplier: Float = 1.0f,
    val debugArtRotationOffset: Float = 0f,
    val isDebugCalibrationVisible: Boolean = false,
    val isDebugFocusRegionVisible: Boolean = false,
    val debugFocusRadiusMultiplier: Float = 0.48f,
    val debugFocusDisplayThreshold: Float = 0.0f,
    val isDebugOptionsEnabled: Boolean = false,
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

    val searchHistory: StateFlow<List<SearchResultItem>> = settingsStore.nightSkySearchHistory.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList(),
    )

    val isSearchActive = MutableStateFlow(false)

    val selectedStarIndex = MutableStateFlow(-1)
    val selectedConstellationIndex = MutableStateFlow(-1)
    val selectedPlanetId = MutableStateFlow<String?>(null)
    val planetStates = MutableStateFlow<List<PlanetState>>(PlanetaryEphemerisCalculator.computeAll(Instant.now()))

    val isSensorTrackingEnabled: StateFlow<Boolean> = settingsStore.nightSkySensorTracking.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        true,
    )

    @Volatile
    var manualPanRa: Float = 0f
        private set

    @Volatile
    var manualPanDec: Float = 0f
        private set

    private val manualMatrixBuffer = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }
    private val publishedManualMatrix = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }
    private val manualOrientationState = MutableStateFlow<DeviceOrientation?>(null)

    /**
     * Fast zero-allocation accessor for the active camera rotation matrix on the 60 FPS hardware vsync loop.
     * Bypasses Compose StateFlow recomposition for ultra-smooth 60/120 FPS panning.
     */
    fun getCurrentRenderMatrix(outMatrix: FloatArray): Boolean {
        if (isSensorTrackingEnabled.value && !isSensorTrackingPaused.value) {
            orientationTracker.getLiveMatrix(outMatrix)
            return true
        } else {
            System.arraycopy(manualMatrixBuffer, 0, outMatrix, 0, minOf(manualMatrixBuffer.size, outMatrix.size))
            return true
        }
    }

    private val baseCameraFovState = MutableStateFlow(CameraFovCalculator.DEFAULT_PORTRAIT_FOV_DEGREES)
    private val zoomRatioState = MutableStateFlow(1.0f)
    val zoomRatio: StateFlow<Float> = zoomRatioState.asStateFlow()

    // Dynamic effective FOV factoring in pinch-to-zoom (supports deep zoom down to 0.0005°)
    val cameraFovHorizontal: StateFlow<Float> = combine(baseCameraFovState, zoomRatioState) { baseFov, zoom ->
        CameraFovCalculator.computeZoomedFov(baseFov, zoom).coerceIn(0.0005f, 140.0f)
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
        NightVisionTheme.AMBER,
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

    val isSensorTrackingPaused: StateFlow<Boolean> = orientationTracker.isSensorTrackingPaused.asStateFlow()

    val orientation: StateFlow<DeviceOrientation?> = combine(
        isSensorTrackingEnabled,
        isSensorTrackingPaused,
        orientationTracker.orientation,
        manualOrientationState,
    ) { sensorEnabled, sensorPaused, sensorOri, manualOri ->
        if (sensorEnabled && !sensorPaused) sensorOri else manualOri
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val location: StateFlow<GeoLocation> = locationProvider.location
    val hasGpsFix: StateFlow<Boolean> = locationProvider.hasGpsFix

    val keepScreenOn: StateFlow<Boolean> = settingsStore.nightSkyKeepScreenOn.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        false,
    )

    val panAzimuthOffset = MutableStateFlow(0f)
    val panPitchOffset = MutableStateFlow(0f)
    val isCalibrated: StateFlow<Boolean> = combine(isSensorTrackingPaused, isSensorTrackingEnabled) { paused, enabled ->
        paused || !enabled
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

    val isPlanetsVisible: StateFlow<Boolean> = settingsStore.nightSkyPlanetsVisible.stateIn(
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
    val isDebugFocusRegionVisible = MutableStateFlow(false)
    val debugFocusRadiusMultiplier = MutableStateFlow(0.48f)
    val debugFocusDisplayThreshold = MutableStateFlow(0.0f)
    val isDebugOptionsEnabled: StateFlow<Boolean> = settingsStore.debugModeEnabled.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        false,
    )

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
            isPlanetsVisible,
            isConstellationsVisible,
            isConstellationArtVisible,
            selectedBodyState,
            isSensorTrackingEnabled,
            searchQuery,
            searchResults,
            searchHistory,
            isSearchActive,
            selectedStarIndex,
            selectedConstellationIndex,
            debugArtOffsetX,
            debugArtOffsetY,
            debugArtScaleMultiplier,
            debugArtRotationOffset,
            isDebugCalibrationVisible,
            isDebugFocusRegionVisible,
            debugFocusRadiusMultiplier,
            debugFocusDisplayThreshold,
            isDebugOptionsEnabled,
            planetStates,
            selectedPlanetId,
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
        val planetsVisible = args[18] as Boolean
        val constellations = args[19] as Boolean
        val constellationArt = args[20] as Boolean
        val selected = args[21] as CelestialBodyDetails?
        val sensorTracking = args[22] as Boolean
        val query = args[23] as String
        @Suppress("UNCHECKED_CAST")
        val results = args[24] as List<SearchResultItem>
        @Suppress("UNCHECKED_CAST")
        val history = args[25] as List<SearchResultItem>
        val searchActive = args[26] as Boolean
        val starIdx = args[27] as Int
        val constIdx = args[28] as Int
        val dbgOffsetX = args[29] as Float
        val dbgOffsetY = args[30] as Float
        val dbgScale = args[31] as Float
        val dbgRot = args[32] as Float
        val dbgVis = args[33] as Boolean
        val dbgFocus = args[34] as Boolean
        val dbgRadius = args[35] as Float
        val dbgThresh = args[36] as Float
        val dbgOptions = args[37] as Boolean
        @Suppress("UNCHECKED_CAST")
        val planetsList = args[38] as List<PlanetState>
        val planetId = args[39] as String?

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
            isPlanetsVisible = planetsVisible,
            isConstellationsVisible = constellations,
            isConstellationArtVisible = constellationArt,
            isSensorTrackingEnabled = sensorTracking,
            searchQuery = query,
            searchResults = results,
            searchHistory = history,
            isSearchActive = searchActive,
            selectedStarIndex = starIdx,
            selectedConstellationIndex = constIdx,
            selectedPlanetId = planetId,
            selectedBody = selected,
            planetStates = planetsList,
            debugArtOffsetX = dbgOffsetX,
            debugArtOffsetY = dbgOffsetY,
            debugArtScaleMultiplier = dbgScale,
            debugArtRotationOffset = dbgRot,
            isDebugCalibrationVisible = dbgVis,
            isDebugFocusRegionVisible = dbgFocus,
            debugFocusRadiusMultiplier = dbgRadius,
            debugFocusDisplayThreshold = dbgThresh,
            isDebugOptionsEnabled = dbgOptions,
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
                searchIndex = CelestialSearchIndex.buildSearchIndex(cat, planetStates.value)
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

        // Ensure Night Sky AR always starts with pure live sensor tracking (0 offsets)
        panAzimuthOffset.value = 0f
        panPitchOffset.value = 0f
        orientationTracker.resumeSensorTracking()

        // Update magnetic declination on location updates
        viewModelScope.launch {
            locationProvider.location.collect { loc ->
                orientationTracker.updateLocation(loc)
                isNightState.value = SolarPositionCalculator.isNight(Instant.now(), loc)
                if (!isSensorTrackingEnabled.value || isSensorTrackingPaused.value) {
                    updateManualOrientation()
                }
            }
        }

        // Sidereal time, planetary ephemeris & day/night ticker updating every second
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

                // Recalculate dynamic planetary positions
                val updatedPlanets = PlanetaryEphemerisCalculator.computeAll(now)
                planetStates.value = updatedPlanets

                val currentPlanetId = selectedPlanetId.value
                if (currentPlanetId != null) {
                    val currentPlanet = updatedPlanets.firstOrNull { it.id.equals(currentPlanetId, ignoreCase = true) }
                    if (currentPlanet != null) {
                        selectedBodyState.value = createPlanetDetails(currentPlanet)
                    }
                }

                if (!isSensorTrackingEnabled.value || isSensorTrackingPaused.value) {
                    updateManualOrientation()
                }
                delay(1000L)
            }
        }
    }

    private fun updateManualOrientation() {
        val ra = manualPanRa.toDouble()
        val dec = manualPanDec.toDouble()
        val lst = lstDegreesState.value
        val lat = locationProvider.location.value.latitude

        val (az, el) = EquatorialCoordinates.equatorialToHorizontal(ra, dec, lst, lat)
        CelestialMatrixCalculator.computeManualRotationMatrix(az, el, manualMatrixBuffer)

        System.arraycopy(manualMatrixBuffer, 0, publishedManualMatrix, 0, 16)

        manualOrientationState.value = DeviceOrientation(
            rotationMatrix = publishedManualMatrix,
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
     * and smoothly pans the camera to the target's coordinates.
     */
    fun selectCelestialBody(item: SearchResultItem, displayRotation: Int = Surface.ROTATION_0) {
        when (item.type) {
            CelestialType.STAR -> selectStarByIndex(item.indexId)
            CelestialType.CONSTELLATION, CelestialType.ASTERISM -> selectConstellationByIndex(item.indexId)
            CelestialType.PLANET -> item.planetId?.let { selectPlanetById(it) }
        }
        isSearchActive.value = false
        searchQuery.value = ""

        viewModelScope.launch {
            settingsStore.addNightSkySearchHistory(item)
        }

        routeCameraToCoordinates(item.ra.toDouble(), item.dec.toDouble(), displayRotation)
    }

    fun removeSearchHistoryItem(item: SearchResultItem) {
        viewModelScope.launch {
            settingsStore.removeNightSkySearchHistory(item)
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            settingsStore.clearNightSkySearchHistory()
        }
    }

    private var routingAnimationJob: Job? = null

    /**
     * Centers the virtual camera on the currently selected celestial object.
     */
    fun routeCameraToSelection(displayRotation: Int = Surface.ROTATION_0) {
        val body = selectedBodyState.value ?: return
        val ra = body.raDegrees ?: return
        val dec = body.decDegrees ?: return
        routeCameraToCoordinates(ra, dec, displayRotation)
    }

    /**
     * Smoothly rotates/animates the camera to [raDegrees] and [decDegrees]
     * using State Injection via absolute equatorial coordinates and S-curve interpolation.
     */
    fun routeCameraToCoordinates(raDegrees: Double, decDegrees: Double, displayRotation: Int = Surface.ROTATION_0) {
        val lst = lstDegreesState.value
        val lat = locationProvider.location.value.latitude

        // 1. If currently tracking live sensor, capture current pointing direction and pause sensor tracking
        val currentRa: Double
        val currentDec: Double
        if (isSensorTrackingEnabled.value && !isSensorTrackingPaused.value) {
            val liveMatrix = orientationTracker.getLiveMatrix()
            val (liveRa, liveDec) = EquatorialCoordinates.getCurrentCameraRaDec(liveMatrix, lst, lat)
            currentRa = liveRa
            currentDec = liveDec
            manualPanRa = currentRa.toFloat()
            manualPanDec = currentDec.toFloat()
            orientationTracker.isSensorTrackingPaused.value = true
        } else {
            currentRa = manualPanRa.toDouble()
            currentDec = manualPanDec.toDouble()
        }

        // 2. Launch smooth spherical S-curve animation
        animateCameraToCoordinates(currentRa, currentDec, raDegrees, decDegrees)
    }

    private fun animateCameraToCoordinates(startRa: Double, startDec: Double, endRa: Double, endDec: Double) {
        routingAnimationJob?.cancel()
        routingAnimationJob = viewModelScope.launch {
            // Find shortest path modulo 360 across the meridian
            val raDiff = CelestialSearchIndex.getShortestAngularDistance(startRa.toFloat(), endRa.toFloat()).toDouble()
            val finalRa = startRa + raDiff

            val durationMs = 800L
            val startTime = System.currentTimeMillis()

            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                val fraction = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)

                // FastOutSlowIn S-curve for smooth acceleration/deceleration: 3t^2 - 2t^3
                val t = (fraction * fraction * (3f - 2f * fraction)).toDouble()

                val interpolatedRa = (startRa + (finalRa - startRa) * t) % 360.0
                val normalizedRa = if (interpolatedRa < 0.0) interpolatedRa + 360.0 else interpolatedRa
                val interpolatedDec = (startDec + (endDec - startDec) * t).coerceIn(-89.9, 89.9)

                manualPanRa = normalizedRa.toFloat()
                manualPanDec = interpolatedDec.toFloat()
                updateManualOrientation()

                if (fraction >= 1f) break
                delay(16L) // ~60 FPS tick
            }
        }
    }

    private fun createPlanetDetails(planet: PlanetState): CelestialBodyDetails {
        val app = getApplication<Application>()
        val isCzech = java.util.Locale.getDefault().language == "cs"
        val name = planet.localizedName(isCzech)
        val subtitle = if (planet.isMoon) {
            app.getString(R.string.celestial_moon_subtitle)
        } else {
            app.getString(R.string.celestial_planet_subtitle)
        }

        val prop1 = Pair(app.getString(R.string.celestial_distance), planet.distanceFormatted)
        val diamFormatted = String.format(java.util.Locale.US, "%.4f° (%.1f\")", planet.angularDiameterDeg, planet.angularDiameterDeg * 3600f)
        val prop2 = Pair(app.getString(R.string.celestial_angular_diameter), diamFormatted)

        return CelestialBodyDetails(
            name = name,
            subtitle = subtitle,
            property1 = prop1,
            property2 = prop2,
            magnitude = planet.magnitude,
            raDegrees = planet.raDegrees,
            decDegrees = planet.decDegrees,
        )
    }

    /**
     * Selects a dynamic planet or Moon by its ID string and populates [selectedBodyState].
     */
    fun selectPlanetById(planetId: String) {
        val planet = planetStates.value.firstOrNull { it.id.equals(planetId, ignoreCase = true) } ?: return
        selectedPlanetId.value = planet.id
        selectedStarIndex.value = -1
        selectedConstellationIndex.value = -1
        selectedBodyState.value = createPlanetDetails(planet)
    }

    /**
     * Dismisses the celestial object selection.
     */
    fun clearSelectedBody() {
        selectedStarIndex.value = -1
        selectedConstellationIndex.value = -1
        selectedPlanetId.value = null
        selectedBodyState.value = null
    }

    /**
     * Drag pan gesture handling for Night Sky AR with state injection.
     *
     * If currently tracking live sensors, freezes live sensor orientation into manual pan state on first touch.
     * Transforms screen delta (X, Y) into horizontal (Azimuth, Elevation) delta,
     * updates equatorial (RA, Dec) coordinates, and regenerates 100% orthonormal leveled matrix.
     */
    fun onPanGesture(deltaX: Float, deltaY: Float, viewWidth: Float, fovDeg: Float) {
        routingAnimationJob?.cancel()
        routingAnimationJob = null

        val factor = fovDeg / viewWidth.coerceAtLeast(1f)
        val lst = lstDegreesState.value
        val lat = locationProvider.location.value.latitude

        if (isSensorTrackingEnabled.value && !isSensorTrackingPaused.value) {
            // State Injection on first touch drag: freeze live sensor orientation into manual pan state
            val liveMatrix = orientationTracker.getLiveMatrix()
            val (liveRa, liveDec) = EquatorialCoordinates.getCurrentCameraRaDec(liveMatrix, lst, lat)
            manualPanRa = liveRa.toFloat()
            manualPanDec = liveDec.toFloat()
            orientationTracker.isSensorTrackingPaused.value = true
        }

        val deltaAzDeg = deltaX * factor
        val deltaElDeg = deltaY * factor

        val (currentAz, currentEl) = EquatorialCoordinates.equatorialToHorizontal(
            manualPanRa.toDouble(),
            manualPanDec.toDouble(),
            lst,
            lat,
        )

        val newAz = (currentAz - deltaAzDeg + 360.0) % 360.0
        val newEl = (currentEl + deltaElDeg).coerceIn(-89.9, 89.9)

        val (newRa, newDec) = EquatorialCoordinates.horizontalToEquatorial(newAz, newEl, lst, lat)
        manualPanRa = newRa.toFloat()
        manualPanDec = newDec.toFloat()

        // In-place matrix update for zero-recomposition hardware vsync render loop
        CelestialMatrixCalculator.computeManualRotationMatrix(newAz, newEl, manualMatrixBuffer)
    }

    fun adjustPan(deltaAzimuthDeg: Float, deltaElevationDeg: Float) {
        routingAnimationJob?.cancel()
        routingAnimationJob = null

        val lst = lstDegreesState.value
        val lat = locationProvider.location.value.latitude

        if (isSensorTrackingEnabled.value && !isSensorTrackingPaused.value) {
            val liveMatrix = orientationTracker.getLiveMatrix()
            val (liveRa, liveDec) = EquatorialCoordinates.getCurrentCameraRaDec(liveMatrix, lst, lat)
            manualPanRa = liveRa.toFloat()
            manualPanDec = liveDec.toFloat()
            orientationTracker.isSensorTrackingPaused.value = true
        }

        val (currentAz, currentEl) = EquatorialCoordinates.equatorialToHorizontal(
            manualPanRa.toDouble(),
            manualPanDec.toDouble(),
            lst,
            lat,
        )

        val newAz = (currentAz + deltaAzimuthDeg + 360.0) % 360.0
        val newEl = (currentEl + deltaElevationDeg).coerceIn(-89.9, 89.9)

        val (newRa, newDec) = EquatorialCoordinates.horizontalToEquatorial(newAz, newEl, lst, lat)
        manualPanRa = newRa.toFloat()
        manualPanDec = newDec.toFloat()

        // In-place matrix update for zero-recomposition hardware vsync render loop
        CelestialMatrixCalculator.computeManualRotationMatrix(newAz, newEl, manualMatrixBuffer)
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
        routingAnimationJob?.cancel()
        routingAnimationJob = null
        orientationTracker.resumeSensorTracking()
        panAzimuthOffset.value = 0f
        panPitchOffset.value = 0f
        viewModelScope.launch {
            settingsStore.saveNightSkyAzimuthOffset(0f)
            settingsStore.saveNightSkyPitchOffset(0f)
        }
    }

    fun resetCalibration() {
        resumeSensorTracking()
    }

    fun toggleSensorTracking() {
        setSensorTrackingEnabled(!isSensorTrackingEnabled.value)
    }

    fun setSensorTrackingEnabled(enabled: Boolean) {
        routingAnimationJob?.cancel()
        routingAnimationJob = null
        if (!enabled && isSensorTrackingEnabled.value) {
            // Switching from Sensor -> Manual: capture current pointing direction as manual starting point
            val lst = lstDegreesState.value
            val lat = locationProvider.location.value.latitude
            val liveMatrix = orientationTracker.getLiveMatrix()
            val (ra, dec) = EquatorialCoordinates.getCurrentCameraRaDec(liveMatrix, lst, lat)
            manualPanRa = ra.toFloat()
            manualPanDec = dec.toFloat()
            updateManualOrientation()
            viewModelScope.launch(Dispatchers.Default) {
                orientationTracker.stop()
            }
        } else if (enabled && !isSensorTrackingEnabled.value) {
            orientationTracker.resumeSensorTracking()
            viewModelScope.launch(Dispatchers.Default) {
                orientationTracker.start()
            }
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
        val updated = (current * factor).coerceIn(MIN_ZOOM, MAX_SAFE_ZOOM)
        if (kotlin.math.abs(updated - current) > 0.001f) {
            zoomRatioState.value = updated
        }
    }

    fun setZoomRatio(ratio: Float) {
        zoomRatioState.value = ratio.coerceIn(MIN_ZOOM, MAX_SAFE_ZOOM)
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

    fun togglePlanetsVisible() {
        setPlanetsVisible(!isPlanetsVisible.value)
    }

    fun setPlanetsVisible(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.saveNightSkyPlanetsVisible(enabled)
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

    fun toggleDebugFocusRegion() {
        isDebugFocusRegionVisible.value = !isDebugFocusRegionVisible.value
    }

    fun setDebugFocusRegionVisible(visible: Boolean) {
        isDebugFocusRegionVisible.value = visible
    }

    fun setDebugFocusRadiusMultiplier(v: Float) {
        debugFocusRadiusMultiplier.value = v.coerceIn(0.10f, 1.50f)
    }

    fun setDebugFocusDisplayThreshold(v: Float) {
        debugFocusDisplayThreshold.value = v.coerceIn(0.0f, 1.0f)
    }

    fun resetDebugFocusRegion() {
        debugFocusRadiusMultiplier.value = 0.48f
        debugFocusDisplayThreshold.value = 0.0f
    }

    fun resetDebugCalibration() {
        debugArtOffsetX.value = 0f
        debugArtOffsetY.value = 0f
        debugArtScaleMultiplier.value = 1.0f
        debugArtRotationOffset.value = 0f
    }

    fun startSensors() {
        routingAnimationJob?.cancel()
        routingAnimationJob = null
        orientationTracker.resumeSensorTracking()
        panAzimuthOffset.value = 0f
        panPitchOffset.value = 0f
        if (!isSensorTrackingEnabled.value) {
            setSensorTrackingEnabled(true)
        } else {
            orientationTracker.start()
        }
        locationProvider.startUpdates()
    }

    fun stopSensors() {
        routingAnimationJob?.cancel()
        routingAnimationJob = null
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

    companion object {
        const val MIN_ZOOM = 0.4f
        const val MAX_SAFE_ZOOM = 2500.0f
    }
}
