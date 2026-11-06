package com.dazix.caelis.ui.nightsky

import android.app.Application
import android.view.Surface
import androidx.camera.core.CameraControl
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dazix.caelis.R
import com.dazix.caelis.ar.CameraFovCalculator
import com.dazix.caelis.ar.DeviceOrientation
import com.dazix.caelis.ar.FovResult
import com.dazix.caelis.ar.OrientationTracker
import com.dazix.caelis.ar.SolarLocationProvider
import com.dazix.caelis.ar.pose.SensorPose
import com.dazix.caelis.ar.pose.SensorPoseSource
import com.dazix.caelis.data.HorizonSettings
import com.dazix.caelis.data.SettingsStore
import com.dazix.caelis.data.horizon.HorizonRepository
import com.dazix.caelis.data.horizon.HorizonState
import com.dazix.caelis.data.nightsky.NightSkyCatalog
import com.dazix.caelis.data.nightsky.Star
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.nightsky.CelestialBodyDetails
import com.dazix.caelis.domain.nightsky.FigureLink
import com.dazix.caelis.domain.nightsky.FigureLinkGroup
import com.dazix.caelis.domain.nightsky.FigureMembership
import com.dazix.caelis.domain.nightsky.CelestialMatrixCalculator
import com.dazix.caelis.domain.nightsky.ConstellationArtOpacity
import com.dazix.caelis.domain.nightsky.RealHorizonPolicy
import com.dazix.caelis.domain.nightsky.CelestialSearchIndex
import com.dazix.caelis.domain.nightsky.CelestialType
import com.dazix.caelis.domain.nightsky.CelestialVisibility
import com.dazix.caelis.domain.nightsky.CelestialVisibilityCalculator
import com.dazix.caelis.domain.nightsky.EquatorialCoordinates
import com.dazix.caelis.data.nightsky.SkyNamesResource
import com.dazix.caelis.domain.nightsky.SkyNames
import com.dazix.caelis.domain.nightsky.NightSkyCatalogLoader
import com.dazix.caelis.domain.nightsky.NightVisionActivationMode
import com.dazix.caelis.domain.nightsky.NightVisionState
import com.dazix.caelis.domain.nightsky.NightVisionTheme
import com.dazix.caelis.domain.nightsky.PlanetState
import com.dazix.caelis.domain.nightsky.PlanetaryEphemerisCalculator
import com.dazix.caelis.domain.nightsky.SearchResultItem
import com.dazix.caelis.domain.nightsky.SiderealTime
import com.dazix.caelis.domain.nightsky.VisibilityState
import com.dazix.caelis.domain.nightsky.isTimeInRange
import com.dazix.caelis.domain.solar.GeoLocation
import com.dazix.caelis.domain.solar.SolarPositionCalculator
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.dazix.caelis.domain.nightsky.TimeState
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import com.dazix.caelis.data.location.SavedPlaceRepository
import com.dazix.caelis.domain.location.Observer
import com.dazix.caelis.domain.location.ObserverPolicy
import com.dazix.caelis.domain.location.PlaceScreen
import com.dazix.caelis.domain.location.SavedPlace

data class NightSkyArUiState(
    val timeState: TimeState = TimeState(),
    val localScheduleDate: LocalDate? = null,
    val location: GeoLocation = GeoLocation.DEFAULT,
    val hasGpsFix: Boolean = false,
    val keepScreenOn: Boolean = false,
    val nightVisionState: NightVisionState = NightVisionState(),
    val redVisionMode: Boolean = nightVisionState.isActive,
    val isNight: Boolean = true,
    val isRedVisionOverridden: Boolean = false,
    val isCalibrated: Boolean = false,
    val cameraFovHorizontal: Float = CameraFovCalculator.DEFAULT_PORTRAIT_FOV_DEGREES,
    val isDynamicStreamCalculated: Boolean = false,
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
    val isDsosVisible: Boolean = true,
    val isMeteorShowersVisible: Boolean = false,
    val isConstellationsVisible: Boolean = true,
    val isConstellationArtVisible: Boolean = true,
    val constellationArtOpacity: Float = ConstellationArtOpacity.DEFAULT,
    val isAsterismNamesVisible: Boolean = true,
    val isSensorTrackingEnabled: Boolean = true,
    val searchQuery: String = "",
    val searchResults: List<SearchResultItem> = emptyList(),
    val searchHistory: List<SearchResultItem> = emptyList(),
    val isSearchActive: Boolean = false,
    val selectedStarIndex: Int = -1,
    val selectedConstellationIndex: Int = -1,
    val selectedDsoIndex: Int = -1,
    val selectedMeteorIndex: Int = -1,
    val selectedPlanetId: String? = null,
    val selectedBody: CelestialBodyDetails? = null,
    val visibilityState: VisibilityState = VisibilityState.Loading,
    val planetStates: List<com.dazix.caelis.domain.nightsky.PlanetState> = emptyList(),
    val isDebugFocusRegionVisible: Boolean = false,
    val debugFocusRadiusMultiplier: Float = 0.48f,
    val debugFocusDisplayThreshold: Float = 0.0f,
    val debugPreferences: com.dazix.caelis.data.DebugPreferences = com.dazix.caelis.data.DebugPreferences(),
    val isDebugOptionsEnabled: Boolean = false,
    val horizonState: com.dazix.caelis.data.horizon.HorizonState = com.dazix.caelis.data.horizon.HorizonState.Idle,
    val multipleHitsTarget: NightSkyRenderer.HitTarget.MultipleHits? = null,
    /** Where the sky is computed from: the phone's GPS position, or a saved virtual place. */
    val observer: Observer = ObserverPolicy.observe(GeoLocation.DEFAULT, false, null, ZoneId.systemDefault()),
    val places: List<SavedPlace> = emptyList(),
    val placeSyncEnabled: Boolean = false,
)

@OptIn(FlowPreview::class)
class NightSkyArViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val orientationTracker = OrientationTracker(application)
    private val locationProvider = SolarLocationProvider(application)
    val renderer = NightSkyRenderer()

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

    private val _timeState = MutableStateFlow(TimeState())
    val timeState: StateFlow<TimeState> = _timeState.asStateFlow()

    val selectedStarIndex = MutableStateFlow(-1)
    val selectedConstellationIndex = MutableStateFlow(-1)
    val selectedDsoIndex = MutableStateFlow(-1)
    val selectedMeteorIndex = MutableStateFlow(-1)
    val selectedPlanetId = MutableStateFlow<String?>(null)
    val planetStates = MutableStateFlow<List<PlanetState>>(PlanetaryEphemerisCalculator.computeAll(Instant.now()))

    val isSensorTrackingEnabled: StateFlow<Boolean> = settingsStore.nightSkySensorTracking.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        true,
    )

    @Volatile
    var manualPanAzimuth: Float = 0f
        private set

    @Volatile
    var manualPanElevation: Float = 0f
        private set

    private val manualMatrixBuffer = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }
    private val publishedManualMatrix = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }
    private val manualOrientationState = MutableStateFlow<DeviceOrientation?>(null)

    /** The sensor tracker as a pose source, for the screen's pose rig ([com.dazix.caelis.ar.pose.SkyPoseRig]). */
    val sensorPose: SensorPose = SensorPoseSource(orientationTracker)

    /**
     * Whether the pose is the phone's: sensor tracking is on and no drag is holding it. In manual mode
     * the matrix is the view model's own, and no tracker - sensor or ARCore - may replace it.
     */
    val isLivePoseActive: Boolean
        get() = isSensorTrackingEnabled.value && !isSensorTrackingPaused.value

    /**
     * Fast zero-allocation accessor for the active camera rotation matrix on the 60 FPS hardware vsync loop.
     * Bypasses Compose StateFlow recomposition for ultra-smooth 60/120 FPS panning.
     *
     * [timestampNs] is the camera image's instant (`PreviewFrameClock`), 0 for the live pose.
     */
    fun getCurrentRenderMatrix(timestampNs: Long, outMatrix: FloatArray): Boolean {
        if (isSensorTrackingEnabled.value && !isSensorTrackingPaused.value) {
            orientationTracker.getRenderMatrix(timestampNs, outMatrix)
            return true
        } else {
            System.arraycopy(manualMatrixBuffer, 0, outMatrix, 0, minOf(manualMatrixBuffer.size, outMatrix.size))
            return true
        }
    }

    private val baseCameraFovState = MutableStateFlow(CameraFovCalculator.DEFAULT_PORTRAIT_FOV_DEGREES)
    private val isDynamicStreamCalculatedState = MutableStateFlow(false)
    val isDynamicStreamCalculated: StateFlow<Boolean> = isDynamicStreamCalculatedState.asStateFlow()
    private val zoomRatioState = MutableStateFlow(1.0f)
    val zoomRatio: StateFlow<Float> = zoomRatioState.asStateFlow()

    /**
     * True once the reported FOV is measured from the sensor's crop region, which already contains
     * the part of the zoom the camera itself performed. Only the remainder is virtual.
     */
    private val fovIncludesZoomState = MutableStateFlow(false)

    /** The zoom the camera accepted. Deep zoom goes far past any lens, so the rest stays virtual. */
    private val appliedCameraZoomState = MutableStateFlow(1.0f)

    // Dynamic effective FOV factoring in user pinch-to-zoom (supports deep zoom down to 0.0005°)
    // Directly scaled from the 1.0x baseline hardware FOV by total user zoom, eliminating
    // camera metadata latency and ensuring instantaneous visual geometric scaling across all layers.
    val cameraFovHorizontal: StateFlow<Float> = combine(
        baseCameraFovState,
        zoomRatioState,
    ) { baseFov, zoom ->
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
        NightVisionActivationMode.OFF,
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
        false,
    )

    val isSensorTrackingPaused: StateFlow<Boolean> = orientationTracker.isSensorTrackingPaused.asStateFlow()

    val headingState: StateFlow<com.dazix.caelis.domain.arframe.HeadingState> = orientationTracker.headingState

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
    val isApproximateLocation: StateFlow<Boolean> = locationProvider.isApproximate

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

    // ==== Terrain horizon ====
    //
    // The repository is shared behaviour, not shared state: it reads the same cached profiles the
    // Sun AR screen wrote, so walking from one screen to the other costs nothing and needs no
    // second download.
    private val horizonRepository = HorizonRepository.getInstance(application)

    val horizonSettings: StateFlow<HorizonSettings> = settingsStore.horizon.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        HorizonSettings(),
    )

    private val placeRepository = SavedPlaceRepository.getInstance(application)

    /**
     * Where the sky is computed from: the GPS position, or a saved virtual place. Magnetic
     * declination and night vision stay on the GPS position: they belong to the phone and its user.
     */
    val observer: StateFlow<Observer> = combine(
        locationProvider.location,
        locationProvider.hasGpsFix,
        placeRepository.placeFor(PlaceScreen.NIGHT_SKY),
    ) { gps, fix, place ->
        ObserverPolicy.observe(gps, fix, place, ZoneId.systemDefault())
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ObserverPolicy.observe(
            locationProvider.location.value,
            locationProvider.hasGpsFix.value,
            placeRepository.placeFor(PlaceScreen.NIGHT_SKY).value,
            ZoneId.systemDefault(),
        ),
    )
    private val observerLocation: StateFlow<GeoLocation> = observer.map { it.location }
        .stateIn(viewModelScope, SharingStarted.Eagerly, observer.value.location)

    val places: StateFlow<List<SavedPlace>> = placeRepository.places
    val placeSyncEnabled: StateFlow<Boolean> = placeRepository.selection.map { it.sync }
        .stateIn(viewModelScope, SharingStarted.Eagerly, placeRepository.selection.value.sync)

    val horizonState: StateFlow<HorizonState> = horizonRepository.state

    /**
     * The shared profile, only while it belongs to this screen's observer: the repository holds one
     * profile for the whole app, and Sun AR may have asked for another place a moment ago.
     */
    val horizonProfile: StateFlow<HorizonProfile?> = combine(horizonRepository.profile, observerLocation) { profile, loc ->
        profile?.takeIf { ObserverPolicy.profileBelongsTo(it, loc) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Replace the stylised tree line with the terrain around you: flat until the profile arrives,
     * stylised again only if it is not coming ([RealHorizonPolicy]).
     */
    val useRealHorizon: StateFlow<Boolean> = combine(horizonSettings, horizonProfile, horizonState) { cfg, profile, state ->
        RealHorizonPolicy.isWanted(
            featureEnabled = cfg.enabled,
            realHorizonInNightSky = cfg.realHorizonInNightSky,
            hasProfile = profile != null,
            profileUnavailable = state is HorizonState.Failed || state is HorizonState.NeedsTiles,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The stylised landscape, by its own switch or in place of a real skyline that is not coming. */
    val showStylisedLandscape: StateFlow<Boolean> =
        combine(isLandscapeVisible, horizonSettings, horizonProfile, horizonState) { switch, cfg, profile, state ->
            RealHorizonPolicy.showsStylisedLandscape(
                landscapeSwitch = switch,
                featureEnabled = cfg.enabled,
                realHorizonInNightSky = cfg.realHorizonInNightSky,
                hasProfile = profile != null,
                profileUnavailable = state is HorizonState.Failed || state is HorizonState.NeedsTiles,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val canDownloadTerrain: Boolean = horizonRepository.canDownload

    private val showConsentDialogState = MutableStateFlow(false)
    val showConsentDialog: StateFlow<Boolean> = showConsentDialogState.asStateFlow()

    fun dismissConsentDialog() {
        showConsentDialogState.value = false
    }

    fun answerNetworkConsent(granted: Boolean, wifiOnly: Boolean? = null) {
        showConsentDialogState.value = false
        viewModelScope.launch {
            val current = settingsStore.currentHorizon()
            val updated = current.copy(
                networkConsent = granted,
                enabled = true,
                realHorizonInNightSky = true,
                wifiOnly = if (wifiOnly != null) wifiOnly else current.wifiOnly,
            )
            settingsStore.saveHorizon(updated)
            settingsStore.saveNightSkyLandscapeVisible(false)
            if (granted) {
                requestProfileHere(updated)
            }
        }
    }

    fun setRealHorizonInNightSky(enabled: Boolean) {
        if (enabled) {
            val current = horizonSettings.value
            if (!current.networkConsentAnswered && canDownloadTerrain) {
                showConsentDialogState.value = true
                return
            }
        }
        viewModelScope.launch {
            val current = settingsStore.currentHorizon()
            val updated = current.copy(
                realHorizonInNightSky = enabled,
                enabled = current.enabled || enabled,
            )
            settingsStore.saveHorizon(updated)
            if (enabled && isLandscapeVisible.value) {
                settingsStore.saveNightSkyLandscapeVisible(false)
            }
            if (enabled && updated.networkDownloadAllowed) {
                requestProfileHere(updated)
            }
        }
    }

    /**
     * Asks for the profile of where this screen looks from. A virtual place is exact and asks at once;
     * GPS waits for a fix (the default fallback must never become a skyline).
     */
    private suspend fun requestProfileHere(settings: HorizonSettings) {
        val obs = observer.value
        if (obs.isVirtual || locationProvider.hasGpsFix.value) {
            horizonRepository.ensureProfile(obs.location, settings)
            return
        }
        horizonRepository.setLocating(locationProvider.satellitesAvailable())
        val hasFix = locationProvider.awaitGpsFix()
        // The user may have picked a place while the GPS was being waited for.
        val now = observer.value
        if (hasFix || now.isVirtual) {
            horizonRepository.ensureProfile(now.location, settings)
        } else {
            horizonRepository.setLocationFailed()
        }
    }

    fun toggleRealHorizonInNightSky() = setRealHorizonInNightSky(!horizonSettings.value.realHorizonInNightSky)

    /** Show or hide the peak names on the real skyline. Needs no download: the names are in the profile. */
    fun togglePeakNames() {
        viewModelScope.launch {
            val current = settingsStore.currentHorizon()
            settingsStore.saveHorizon(current.copy(peakNamesInNightSky = !current.peakNamesInNightSky))
        }
    }

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

    val isDsosVisible: StateFlow<Boolean> = settingsStore.nightSkyDsosVisible.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        false,
    )

    val isMeteorShowersVisible: StateFlow<Boolean> = settingsStore.nightSkyMeteorShowersVisible.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        false,
    )

    fun toggleMeteorShowersVisible() {
        viewModelScope.launch {
            settingsStore.saveNightSkyMeteorShowersVisible(!isMeteorShowersVisible.value)
        }
    }

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

    /**
     * The slider's value while a finger is on it. A drag shows every step on the sky at once but
     * writes DataStore only when it ends, not on every one of the dozens of steps in between.
     */
    private val constellationArtOpacityDrag = MutableStateFlow<Float?>(null)

    val constellationArtOpacity: StateFlow<Float> = combine(
        settingsStore.nightSkyConstellationArtOpacity,
        constellationArtOpacityDrag,
    ) { stored, dragged -> dragged ?: stored }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ConstellationArtOpacity.DEFAULT,
    )

    val isAsterismNamesVisible: StateFlow<Boolean> = settingsStore.nightSkyAsterismNamesVisible.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        true,
    )

    // In-App Calibration Debugger StateFlows
    val isDebugFocusRegionVisible = MutableStateFlow(false)
    val debugFocusRadiusMultiplier = MutableStateFlow(0.48f)
    val debugFocusDisplayThreshold = MutableStateFlow(0.0f)
    val debugPreferences: StateFlow<com.dazix.caelis.data.DebugPreferences> = settingsStore.debugPreferences.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        com.dazix.caelis.data.DebugPreferences(),
    )

    init {
        viewModelScope.launch {
            debugPreferences.collect { orientationTracker.compassLogEnabled = it.logCompass }
        }
    }
    val isDebugOptionsEnabled: StateFlow<Boolean> = settingsStore.debugPreferences
        .map { it.enableNightSkyFocusRegion }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            false,
        )

    private val selectedBodyState = MutableStateFlow<CelestialBodyDetails?>(null)
    val selectedBody: StateFlow<CelestialBodyDetails?> = selectedBodyState.asStateFlow()

    private var visibilityCalculationJob: Job? = null
    private val _visibilityState = MutableStateFlow<VisibilityState>(VisibilityState.Loading)
    val visibilityState: StateFlow<VisibilityState> = _visibilityState.asStateFlow()

    private val _localScheduleDate = MutableStateFlow<LocalDate?>(null)
    val localScheduleDate: StateFlow<LocalDate?> = _localScheduleDate.asStateFlow()

    private val multipleHitsTargetState = MutableStateFlow<NightSkyRenderer.HitTarget.MultipleHits?>(null)
    val multipleHitsTarget: StateFlow<NightSkyRenderer.HitTarget.MultipleHits?> = multipleHitsTargetState.asStateFlow()

    val uiState: StateFlow<NightSkyArUiState> = combine(
        listOf(
            observerLocation,
            locationProvider.hasGpsFix,
            keepScreenOn,
            nightVisionState,
            isNightState,
            manualRedVisionOverride,
            isCalibrated,
            cameraFovHorizontal,
            isDynamicStreamCalculatedState,
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
            isDsosVisible,
            isMeteorShowersVisible,
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
            selectedDsoIndex,
            selectedMeteorIndex,
            isDebugFocusRegionVisible,
            debugFocusRadiusMultiplier,
            debugFocusDisplayThreshold,
            isDebugOptionsEnabled,
            planetStates,
            selectedPlanetId,
            horizonState,
            debugPreferences,
            multipleHitsTargetState,
            _timeState,
            _visibilityState,
            _localScheduleDate,
            constellationArtOpacity,
            isAsterismNamesVisible,
            observer,
            places,
            placeSyncEnabled,
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
        val isDynamicFov = args[8] as Boolean
        val zoom = args[9] as Float
        val lstDeg = args[10] as Double
        val lstFmt = args[11] as String
        val visibleStars = args[12] as Int
        val cat = args[13] as NightSkyCatalog?
        val opaque = args[14] as Boolean
        val milkyWay = args[15] as Boolean
        val atmosphere = args[16] as Boolean
        val landscape = args[17] as Boolean
        val grid = args[18] as Boolean
        val planetsVisible = args[19] as Boolean
        val dsosVisible = args[20] as Boolean
        val meteorsVisible = args[21] as Boolean
        val constellations = args[22] as Boolean
        val constellationArt = args[23] as Boolean
        val selected = args[24] as CelestialBodyDetails?
        val sensorTracking = args[25] as Boolean
        val query = args[26] as String
        @Suppress("UNCHECKED_CAST")
        val results = args[27] as List<SearchResultItem>
        @Suppress("UNCHECKED_CAST")
        val history = args[28] as List<SearchResultItem>
        val searchActive = args[29] as Boolean
        val starIdx = args[30] as Int
        val constIdx = args[31] as Int
        val dsoIdx = args[32] as Int
        val meteorIdx = args[33] as Int
        val dbgFocus = args[34] as Boolean
        val dbgRadius = args[35] as Float
        val dbgThresh = args[36] as Float
        val dbgOptions = args[37] as Boolean
        @Suppress("UNCHECKED_CAST")
        val planetsList = args[38] as List<PlanetState>
        val planetId = args[39] as String?
        val horizState = args[40] as com.dazix.caelis.data.horizon.HorizonState
        val debugPrefs = args[41] as com.dazix.caelis.data.DebugPreferences
        val multipleHits = args[42] as NightSkyRenderer.HitTarget.MultipleHits?
        val tState = args[43] as TimeState
        val visState = args[44] as VisibilityState
        val localDate = args[45] as LocalDate?
        val artOpacity = args[46] as Float
        val asterismNames = args[47] as Boolean
        val obs = args[48] as Observer
        @Suppress("UNCHECKED_CAST")
        val placeList = args[49] as List<SavedPlace>
        val sync = args[50] as Boolean

        NightSkyArUiState(
            timeState = tState,
            localScheduleDate = localDate,
            location = loc,
            hasGpsFix = gps,
            keepScreenOn = keepOn,
            nightVisionState = nvState,
            redVisionMode = nvState.isActive,
            isNight = night,
            isRedVisionOverridden = redOverride != null,
            isCalibrated = calib,
            cameraFovHorizontal = fov,
            isDynamicStreamCalculated = isDynamicFov,
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
            isDsosVisible = dsosVisible,
            isMeteorShowersVisible = meteorsVisible,
            isConstellationsVisible = constellations,
            isConstellationArtVisible = constellationArt,
            constellationArtOpacity = artOpacity,
            isAsterismNamesVisible = asterismNames,
            isSensorTrackingEnabled = sensorTracking,
            searchQuery = query,
            searchResults = results,
            searchHistory = history,
            isSearchActive = searchActive,
            selectedStarIndex = starIdx,
            selectedConstellationIndex = constIdx,
            selectedDsoIndex = dsoIdx,
            selectedMeteorIndex = meteorIdx,
            selectedPlanetId = planetId,
            selectedBody = selected,
            visibilityState = visState,
            planetStates = planetsList,
            isDebugFocusRegionVisible = dbgFocus,
            debugFocusRadiusMultiplier = dbgRadius,
            debugFocusDisplayThreshold = dbgThresh,
            debugPreferences = debugPrefs,
            isDebugOptionsEnabled = dbgOptions,
            horizonState = horizState,
            multipleHitsTarget = multipleHits,
            observer = obs,
            places = placeList,
            placeSyncEnabled = sync,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NightSkyArUiState())

    init {
        // Load FlatBuffers Star Catalog binary asynchronously from res/raw/catalog.bin,
        // precompute J2000 celestial vectors, and build search index on background thread (Dispatchers.Default).
        viewModelScope.launch {
            renderer.skyNames = skyNames()
            val (cat, index) = withContext(Dispatchers.Default) {
                val catalog = withContext(Dispatchers.IO) {
                    try {
                        application.resources.openRawResource(R.raw.catalog).use { stream ->
                            NightSkyCatalogLoader.loadFromStream(stream)
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                val builtIndex = if (catalog != null) {
                    renderer.ensureStaticCelestialVectors(catalog)
                    CelestialSearchIndex.buildSearchIndex(catalog, planetStates.value, skyNames())
                } else {
                    emptyList()
                }
                Pair(catalog, builtIndex)
            }
            searchIndex = index
            catalogState.value = cat
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

        // Start with live sensor tracking, but keep the standing calibration: it is a correction for
        // this phone's compass, not a pan the user forgot to end. Restoring it through
        // setCalibration leaves the sensors running; setPanOffsets would have frozen them.
        orientationTracker.resumeSensorTracking()
        viewModelScope.launch {
            val savedAz = settingsStore.arAzimuthOffset.firstOrNull() ?: 0f
            val savedPitch = settingsStore.arElevationOffset.firstOrNull() ?: 0f
            panAzimuthOffset.value = savedAz
            panPitchOffset.value = savedPitch
            orientationTracker.setCalibration(savedAz, savedPitch)
        }

        // Update magnetic declination on location updates
        viewModelScope.launch {
            locationProvider.location.collect { loc ->
                orientationTracker.updateLocation(loc)
                val activeTime = _timeState.value.activeInstant()
                isNightState.value = SolarPositionCalculator.isNight(activeTime, loc)
                if (!isSensorTrackingEnabled.value || isSensorTrackingPaused.value) {
                    updateManualOrientation()
                }
            }
        }

        // A new place moves the whole sky at once, also while the virtual clock is frozen (the ticker
        // below only runs on live time).
        viewModelScope.launch {
            observer.map { it.place?.id }.distinctUntilChanged().drop(1).collect {
                recomputeAstronomicalState(_timeState.value.activeInstant())
            }
        }

        // Recompute visibility on debounced location changes
        viewModelScope.launch {
            observerLocation
                .debounce(2_000L)
                .collect {
                    selectedBodyState.value?.let { body ->
                        fetchVisibilityForSelectedBody(body.raDegrees, body.decDegrees, isInitialSelection = false)
                    }
                }
        }

        // Recompute visibility when horizon terrain profile changes
        viewModelScope.launch {
            horizonProfile.collect {
                selectedBodyState.value?.let { body ->
                    fetchVisibilityForSelectedBody(body.raDegrees, body.decDegrees, isInitialSelection = false)
                }
            }
        }

        // The first visit to an AR screen asks the download question, as Sun AR does: answering it is
        // what switches the real horizon on. Read from the store, not from [horizonSettings], whose
        // placeholder before the first read has no answer.
        viewModelScope.launch {
            if (!settingsStore.currentHorizon().networkConsentAnswered && canDownloadTerrain) {
                showConsentDialogState.value = true
            }
        }

        // Keep a terrain profile for wherever the observer is, on the same terms as the Sun AR
        // screen: debounced, rounded to about 110 m, and a no-op when the layer is switched off.
        // The first usable fix goes through at once: debouncing it only delayed the stored profile,
        // and the repository already ignores repeats of the same rounded position.
        viewModelScope.launch {
            // A virtual place is exact, so it counts as a fix: no waiting, no GPS.
            var firstFixSeen = false
            combine(observer, horizonSettings) { obs, cfg -> obs to cfg }
                .debounce { (obs, _) ->
                    if (!firstFixSeen && obs.canRequestProfile) {
                        firstFixSeen = true
                        0L
                    } else {
                        1_000L
                    }
                }
                .collect { (obs, cfg) ->
                    if (obs.canRequestProfile && cfg.realHorizonInNightSky) {
                        horizonRepository.ensureProfile(obs.location, cfg)
                    }
                }
        }


        // Sidereal time, planetary ephemeris & day/night ticker updating every second
        viewModelScope.launch {
            while (isActive) {
                if (!_timeState.value.isVirtual) {
                    val loc = locationProvider.location.value
                    val now = Instant.now()
                    val lstDeg = SiderealTime.computeLstDegrees(now, observer.value.location.longitude)
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
                    visibleStarCountState.value = renderer.lastVisibleStarCount
                }
                delay(1000L)
            }
        }
    }

    /**
     * Activates virtual time and immediately recalculates all astronomical models.
     */
    fun setVirtualTime(instant: Instant) {
        _timeState.value = TimeState(isVirtual = true, virtualTime = instant)
        recomputeAstronomicalState(instant)
    }

    /**
     * Resets back to live device clock.
     */
    fun resetToLiveTime() {
        _timeState.value = TimeState(isVirtual = false, virtualTime = null)
        recomputeAstronomicalState(Instant.now())
    }

    /**
     * Increments or decrements active virtual time by [duration].
     */
    fun stepVirtualTime(duration: Duration) {
        val base = _timeState.value.virtualTime ?: Instant.now()
        val target = base.plus(duration)
        setVirtualTime(target)
    }

    private fun recomputeAstronomicalState(instant: Instant) {
        val loc = locationProvider.location.value
        val lstDeg = SiderealTime.computeLstDegrees(instant, observer.value.location.longitude)
        val lstHours = lstDeg / 15.0
        lstDegreesState.value = lstDeg
        lstFormattedState.value = SiderealTime.formatLstHours(lstHours)
        // Night vision protects the eyes of whoever holds the phone: its night is the GPS one.
        isNightState.value = SolarPositionCalculator.isNight(instant, loc)
        currentTimeState.value = instant.atZone(ZoneId.systemDefault()).toLocalTime()

        // Recalculate dynamic planetary positions
        val updatedPlanets = PlanetaryEphemerisCalculator.computeAll(instant)
        planetStates.value = updatedPlanets

        val currentPlanetId = selectedPlanetId.value
        if (currentPlanetId != null) {
            val currentPlanet = updatedPlanets.firstOrNull { it.id.equals(currentPlanetId, ignoreCase = true) }
            if (currentPlanet != null) {
                selectedBodyState.value = createPlanetDetails(currentPlanet)
            }
        }

        selectedBodyState.value?.let { body ->
            fetchVisibilityForSelectedBody(body.raDegrees, body.decDegrees, isInitialSelection = false)
        }
    }

    private fun fetchVisibilityForSelectedBody(
        raDegrees: Double?,
        decDegrees: Double?,
        isInitialSelection: Boolean = false,
    ) {
        visibilityCalculationJob?.cancel()
        if (raDegrees == null || decDegrees == null) {
            _visibilityState.value = VisibilityState.Error
            return
        }

        if (isInitialSelection || _visibilityState.value !is VisibilityState.Loaded) {
            _visibilityState.value = VisibilityState.Loading
        }
        val targetDate = _localScheduleDate.value
        val obs = observer.value
        val instant = if (targetDate != null) {
            targetDate.atTime(12, 0).atZone(obs.zone).toInstant()
        } else {
            _timeState.value.activeInstant()
        }
        val loc = obs.location
        val profile = if (horizonSettings.value.realHorizonInNightSky && horizonSettings.value.enabled) {
            horizonProfile.value
        } else null

        val planetId = selectedPlanetId.value
        visibilityCalculationJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val (targetRa, targetDec) = if (planetId != null) {
                    val dynamicPlanets = PlanetaryEphemerisCalculator.computeAll(instant)
                    val p = dynamicPlanets.firstOrNull { it.id.equals(planetId, ignoreCase = true) }
                    if (p != null) Pair(p.raDegrees, p.decDegrees) else Pair(raDegrees, decDegrees)
                } else {
                    Pair(raDegrees, decDegrees)
                }

                val vis = CelestialVisibilityCalculator.calculateVisibility(
                    raDegrees = targetRa,
                    decDegrees = targetDec,
                    referenceInstant = instant,
                    location = loc,
                    profile = profile,
                )
                _visibilityState.value = VisibilityState.Loaded(vis)
            } catch (_: Exception) {
                if (isInitialSelection || _visibilityState.value !is VisibilityState.Loaded) {
                    _visibilityState.value = VisibilityState.Error
                }
            }
        }
    }

    /**
     * Overrides the calendar date exclusively for the visibility schedule of the active celestial body.
     * Pass `null` to revert back to the global application date.
     */
    fun setLocalScheduleDate(date: LocalDate?) {
        _localScheduleDate.value = date
        selectedBodyState.value?.let { body ->
            fetchVisibilityForSelectedBody(body.raDegrees, body.decDegrees, isInitialSelection = false)
        }
    }

    /**
     * Steps the local schedule date forward or backward by [days].
     */
    fun stepLocalScheduleDate(days: Long) {
        val activeTime = _timeState.value.activeInstant()
        val baseDate = _localScheduleDate.value ?: activeTime.atZone(observer.value.zone).toLocalDate()
        setLocalScheduleDate(baseDate.plusDays(days))
    }

    /**
     * Resets the local schedule date override, returning to global application time.
     */
    fun resetLocalScheduleDate() {
        setLocalScheduleDate(null)
    }

    private fun captureLiveOrientationIntoManual() {
        val liveMatrix = orientationTracker.getLiveMatrix()
        // Camera optical forward axis in ENU: V = [-R[2], -R[6], -R[10]]^T
        val vx = -liveMatrix[2].toDouble()
        val vy = -liveMatrix[6].toDouble()
        val vz = -liveMatrix[10].toDouble()
        var az = Math.toDegrees(kotlin.math.atan2(vx, vy))
        if (az < 0.0) az += 360.0
        val el = Math.toDegrees(kotlin.math.asin(vz.coerceIn(-1.0, 1.0)))
        manualPanAzimuth = az.toFloat()
        manualPanElevation = el.toFloat()
        updateManualOrientation()
    }

    private fun updateManualOrientation() {
        val az = manualPanAzimuth.toDouble()
        val el = manualPanElevation.toDouble()

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

    fun setCameraFov(fovResult: FovResult) {
        fovIncludesZoomState.value = fovResult.isDynamicStreamCalculated
        isDynamicStreamCalculatedState.value = fovResult.isDynamicStreamCalculated
        val targetBaseFov = fovResult.baseHorizontalDegrees
        if (kotlin.math.abs(baseCameraFovState.value - targetBaseFov) > 0.1f) {
            baseCameraFovState.value = targetBaseFov
        }
    }

    /**
     * The field of view predicted from the lens while the camera is off ([stillBackCameraFov]). A
     * FOV measured from the stream earlier in this screen is the better number and stays.
     */
    fun setPredictedCameraFov(fovResult: FovResult) {
        if (isDynamicStreamCalculatedState.value) return
        setCameraFov(fovResult)
    }

    fun setCameraFov(fovDeg: Float, includesZoom: Boolean = false) {
        setCameraFov(FovResult(horizontalDegrees = fovDeg, isDynamicStreamCalculated = includesZoom, baseHorizontalDegrees = fovDeg))
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
        dismissDisambiguation()
        val cat = catalogState.value ?: return
        val totalStars = cat.starsLength()
        if (starIndex !in 0 until totalStars) {
            selectedStarIndex.value = -1
            selectedBodyState.value = null
            return
        }

        selectedStarIndex.value = starIndex
        selectedConstellationIndex.value = -1
        selectedDsoIndex.value = -1
        selectedMeteorIndex.value = -1
        selectedPlanetId.value = null
        _localScheduleDate.value = null

        val star = Star()
        cat.stars(star, starIndex)

        // Query metadata vector for named star entry matching this index
        val metaLen = cat.metadataLength()
        val reusableMeta = com.dazix.caelis.data.nightsky.StarMetadata()
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
        val names = skyNames()
        val subtitle = names.starType(matchedType) ?: app.getString(R.string.celestial_star_subtitle)
        val distanceFormatted = if (matchedDistanceLy > 0f) {
            String.format(java.util.Locale.US, "%.1f ly", matchedDistanceLy)
        } else {
            "—"
        }

        val membership = figureMembership(cat)
        val constellationIndex = membership.constellationForStar(starIndex, matchedConstellation)
        // A constellation the catalog names but has no figure for stays a plain value.
        val prop1 = if (constellationIndex == null) {
            names.constellationOfCode(matchedConstellation)?.let {
                Pair(app.getString(R.string.celestial_constellation), it)
            }
        } else {
            null
        }
        val prop2 = Pair(app.getString(R.string.celestial_distance), distanceFormatted)

        val figures = buildList {
            if (constellationIndex != null) {
                add(figureGroup(cat, app.resources.getQuantityString(R.plurals.celestial_constellations_label, 1), listOf(constellationIndex)))
            }
            val asterisms = membership.asterismsOfStar(starIndex)
            if (asterisms.isNotEmpty()) {
                add(figureGroup(cat, app.resources.getQuantityString(R.plurals.celestial_asterisms_label, asterisms.size), asterisms))
            }
        }

        selectedBodyState.value = CelestialBodyDetails(
            name = name,
            subtitle = subtitle,
            property1 = prop1,
            property2 = prop2,
            magnitude = star.mag(),
            raDegrees = star.ra().toDouble(),
            decDegrees = star.dec().toDouble(),
            figures = figures,
        )
        fetchVisibilityForSelectedBody(star.ra().toDouble(), star.dec().toDouble(), isInitialSelection = true)
    }

    /**
     * Resolves astronomical metadata for the tapped constellation from FlatBuffers catalog and updates [selectedBody].
     */
    fun selectConstellationByIndex(constellationIndex: Int) {
        dismissDisambiguation()
        val cat = catalogState.value ?: return
        val totalConstellations = cat.constellationsLength()
        if (constellationIndex !in 0 until totalConstellations) {
            selectedConstellationIndex.value = -1
            selectedBodyState.value = null
            return
        }

        selectedConstellationIndex.value = constellationIndex
        selectedStarIndex.value = -1
        selectedDsoIndex.value = -1
        selectedMeteorIndex.value = -1
        selectedPlanetId.value = null
        _localScheduleDate.value = null

        val reusableConst = com.dazix.caelis.data.nightsky.Constellation()
        cat.constellations(reusableConst, constellationIndex)

        val app = getApplication<Application>()
        val isAsterism = reusableConst.isAsterism()
        val name = skyNames().constellation(reusableConst.id(), isAsterism, reusableConst.name()) ?: ""
        val subtitle = if (isAsterism) {
            app.getString(R.string.celestial_asterism_subtitle)
        } else {
            app.getString(R.string.celestial_constellation_subtitle)
        }
        val brightestStar = reusableConst.brightestStar() ?: "—"
        val family = reusableConst.family() ?: "—"
        val prop1 = Pair(app.getString(R.string.celestial_brightest_star), brightestStar)
        // An asterism's parents come from the lines it shares with them, translated and selectable;
        // the catalog's English `family` text is the fallback for one that shares no line star.
        val parents = if (isAsterism) figureMembership(cat).parentsOfAsterism(constellationIndex) else emptyList()
        val prop2 = when {
            isAsterism && parents.isNotEmpty() -> null
            isAsterism -> Pair(app.getString(R.string.celestial_parent_constellation), family)
            else -> Pair(app.getString(R.string.celestial_family), family)
        }
        val figures = if (parents.isEmpty()) {
            emptyList()
        } else {
            listOf(figureGroup(cat, app.resources.getQuantityString(R.plurals.celestial_parent_constellations_label, parents.size), parents))
        }

        val ra = reusableConst.centroidRa().toDouble()
        val dec = reusableConst.centroidDec().toDouble()

        selectedBodyState.value = CelestialBodyDetails(
            name = name,
            subtitle = subtitle,
            property1 = prop1,
            property2 = prop2,
            magnitude = null,
            raDegrees = ra,
            decDegrees = dec,
            figures = figures,
        )
        fetchVisibilityForSelectedBody(ra, dec, isInitialSelection = true)
    }

    /**
     * Resolves metadata for the tapped Deep Sky Object from FlatBuffers catalog and updates [selectedBody].
     */
    fun selectDsoByIndex(dsoIndex: Int) {
        dismissDisambiguation()
        val cat = catalogState.value ?: return
        val totalDsos = cat.dsosLength()
        if (dsoIndex !in 0 until totalDsos) {
            selectedDsoIndex.value = -1
            selectedBodyState.value = null
            return
        }

        selectedDsoIndex.value = dsoIndex
        selectedStarIndex.value = -1
        selectedConstellationIndex.value = -1
        selectedMeteorIndex.value = -1
        selectedPlanetId.value = null
        _localScheduleDate.value = null

        val reusableDso = com.dazix.caelis.data.nightsky.DeepSkyObject()
        cat.dsos(reusableDso, dsoIndex)

        val app = getApplication<Application>()
        val mNum = reusableDso.messierId().toInt()
        val messierId = "M$mNum"
        val dsoName = skyNames().messier(mNum, reusableDso.englishName())
        val name = if (!dsoName.isNullOrEmpty()) "$messierId ($dsoName)" else messierId

        val dsoTypeStr = when (reusableDso.type()) {
            com.dazix.caelis.data.nightsky.DsoType.GALAXY -> app.getString(R.string.dso_type_galaxy)
            com.dazix.caelis.data.nightsky.DsoType.OPEN_CLUSTER -> app.getString(R.string.dso_type_open_cluster)
            com.dazix.caelis.data.nightsky.DsoType.GLOBULAR_CLUSTER -> app.getString(R.string.dso_type_globular_cluster)
            com.dazix.caelis.data.nightsky.DsoType.NEBULA -> app.getString(R.string.dso_type_diffuse_nebula)
            com.dazix.caelis.data.nightsky.DsoType.PLANETARY_NEBULA -> app.getString(R.string.dso_type_planetary_nebula)
            else -> app.getString(R.string.dso_type_generic)
        }

        val ngc = reusableDso.ngcId()
        val sizeArcmin = reusableDso.sizeArcmin()
        val sizeFormatted = if (sizeArcmin > 0f) {
            if (sizeArcmin >= 60f) String.format(java.util.Locale.US, "%.1f°", sizeArcmin / 60f)
            else String.format(java.util.Locale.US, "%.1f'", sizeArcmin)
        } else "—"

        val prop1: Pair<String, String>? = if (ngc != null) Pair(app.getString(R.string.celestial_catalog_id), ngc) else null
        val prop2: Pair<String, String>? = Pair(app.getString(R.string.celestial_angular_size), sizeFormatted)

        val dsoRa = reusableDso.ra().toDouble()
        val dsoDec = reusableDso.dec().toDouble()

        selectedBodyState.value = CelestialBodyDetails(
            name = name,
            subtitle = dsoTypeStr,
            property1 = prop1,
            property2 = prop2,
            magnitude = reusableDso.mag(),
            raDegrees = dsoRa,
            decDegrees = dsoDec,
        )
        fetchVisibilityForSelectedBody(dsoRa, dsoDec, isInitialSelection = true)
    }

    /**
     * Resolves metadata for the tapped Meteor Shower Radiant from FlatBuffers catalog and updates [selectedBody].
     */
    fun selectMeteorByIndex(meteorIndex: Int) {
        dismissDisambiguation()
        val cat = catalogState.value ?: return
        val totalMeteors = cat.meteorShowersLength()
        if (meteorIndex !in 0 until totalMeteors) {
            selectedMeteorIndex.value = -1
            selectedBodyState.value = null
            return
        }

        selectedMeteorIndex.value = meteorIndex
        selectedStarIndex.value = -1
        selectedConstellationIndex.value = -1
        selectedDsoIndex.value = -1
        selectedPlanetId.value = null
        _localScheduleDate.value = null

        val reusableMeteor = com.dazix.caelis.data.nightsky.MeteorShower()
        cat.meteorShowers(reusableMeteor, meteorIndex)

        val app = getApplication<Application>()
        val names = skyNames()
        val englishName = reusableMeteor.name() ?: "Meteor Shower"
        val name = names.meteorShower(reusableMeteor.id().toInt(), englishName) ?: englishName
        val subtitle = app.getString(R.string.celestial_meteor_shower_subtitle)

        // "12. srpna", "12. August", "12 sierpnia", "Aug 12": the order and the month's case come
        // from the locale's own day-and-month pattern, not from a table per language.
        val locale = app.resources.configuration.locales[0]
        val peakFormatted = try {
            java.time.MonthDay.of(reusableMeteor.peakMonth().toInt(), reusableMeteor.peakDay().toInt()).format(
                java.time.format.DateTimeFormatter.ofPattern(
                    android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMM"),
                    locale,
                )
            )
        } catch (_: java.time.DateTimeException) {
            "${reusableMeteor.peakDay()}/${reusableMeteor.peakMonth()}"
        }

        val zhr = reusableMeteor.zhr()
        val zhrFormatted = String.format(locale, app.getString(R.string.celestial_zhr_format), zhr)

        val constCode = reusableMeteor.constellationId()
        val constIndex = figureMembership(cat).constellationOfCode(constCode)
        val constName = if (!constCode.isNullOrEmpty() && constIndex == null) names.constellationOfCode(constCode) else null

        val prop1: Pair<String, String>? = Pair(app.getString(R.string.celestial_peak), peakFormatted)
        val prop2: Pair<String, String>? = Pair(app.getString(R.string.celestial_zhr), zhrFormatted)
        val prop3: Pair<String, String>? = if (constName != null) Pair(app.getString(R.string.celestial_constellation), constName) else null
        val figures = if (constIndex == null) {
            emptyList()
        } else {
            listOf(figureGroup(cat, app.resources.getQuantityString(R.plurals.celestial_constellations_label, 1), listOf(constIndex)))
        }

        val meteorRa = reusableMeteor.ra().toDouble()
        val meteorDec = reusableMeteor.dec().toDouble()

        selectedBodyState.value = CelestialBodyDetails(
            name = name,
            subtitle = subtitle,
            property1 = prop1,
            property2 = prop2,
            property3 = prop3,
            magnitude = null,
            raDegrees = meteorRa,
            decDegrees = meteorDec,
            figures = figures,
        )
        fetchVisibilityForSelectedBody(meteorRa, meteorDec, isInitialSelection = true)
    }

    /**
     * Follows a change of the app's language: this view model outlives the activity recreated for
     * it, so the sky labels and the search index would otherwise keep the names they were built in.
     * Called by the screen whenever its locale changes; the same names are a no-op.
     */
    fun refreshSkyNames() {
        val names = skyNames()
        if (names === renderer.skyNames) return
        renderer.skyNames = names
        val cat = catalogState.value ?: return
        viewModelScope.launch {
            searchIndex = withContext(Dispatchers.Default) {
                CelestialSearchIndex.buildSearchIndex(cat, planetStates.value, names)
            }
        }
    }

    /** Night-sky names in the app's current language (reloaded after a language change). */
    private fun skyNames(): SkyNames = SkyNamesResource.forResources(getApplication<Application>().resources)

    private var figureMembershipCache: Pair<NightSkyCatalog, FigureMembership>? = null

    /** Built on the first selection after a catalog load (a couple of hundred figures). */
    private fun figureMembership(cat: NightSkyCatalog): FigureMembership {
        figureMembershipCache?.let { (cached, membership) -> if (cached === cat) return membership }
        val reusable = com.dazix.caelis.data.nightsky.Constellation()
        val figures = List(cat.constellationsLength()) { i ->
            cat.constellations(reusable, i)
            FigureMembership.Figure(
                index = i,
                code = reusable.id(),
                isAsterism = reusable.isAsterism(),
                stars = IntArray(reusable.linesLength()) { reusable.lines(it) },
            )
        }
        return FigureMembership(figures).also { figureMembershipCache = cat to it }
    }

    private fun figureGroup(cat: NightSkyCatalog, label: String, indices: List<Int>): FigureLinkGroup {
        val names = skyNames()
        val reusable = com.dazix.caelis.data.nightsky.Constellation()
        val links = indices.map { i ->
            cat.constellations(reusable, i)
            val name = names.constellation(reusable.id(), reusable.isAsterism(), reusable.name())
            FigureLink(name = name ?: reusable.id().orEmpty(), constellationIndex = i)
        }
        return FigureLinkGroup(label, links)
    }

    /**
     * Follows a constellation or asterism named in the detail sheet: selects it, as a tap on its
     * figure would, and turns the view to it as a search result would.
     */
    fun selectFigureFromDetail(constellationIndex: Int, displayRotation: Int = Surface.ROTATION_0) {
        selectConstellationByIndex(constellationIndex)
        if (selectedConstellationIndex.value == constellationIndex) routeCameraToSelection(displayRotation)
    }

    /**
     * Selects a celestial body from search results, updates selection state,
     * and smoothly pans the camera to the target's coordinates.
     */
    fun selectCelestialBody(item: SearchResultItem, displayRotation: Int = Surface.ROTATION_0) {
        when (item.type) {
            CelestialType.STAR -> selectStarByIndex(item.indexId)
            CelestialType.CONSTELLATION, CelestialType.ASTERISM -> selectConstellationByIndex(item.indexId)
            CelestialType.DSO -> selectDsoByIndex(item.indexId)
            CelestialType.METEOR_SHOWER -> selectMeteorByIndex(item.indexId)
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

    private fun currentLstDegrees(): Double {
        val currentMs = if (_timeState.value.isVirtual && _timeState.value.virtualTime != null) {
            _timeState.value.virtualTime!!.toEpochMilli()
        } else {
            System.currentTimeMillis()
        }
        return SiderealTime.computeLstDegrees(currentMs, observer.value.location.longitude)
    }

    /**
     * Smoothly rotates/animates the camera to [raDegrees] and [decDegrees]
     * using State Injection via absolute equatorial coordinates and S-curve interpolation.
     */
    fun routeCameraToCoordinates(raDegrees: Double, decDegrees: Double, displayRotation: Int = Surface.ROTATION_0) {
        val lst = currentLstDegrees()
        val lat = observer.value.location.latitude
        val (targetAz, targetEl) = EquatorialCoordinates.equatorialToHorizontal(raDegrees, decDegrees, lst, lat)

        val currentAz: Double
        val currentEl: Double
        if (isSensorTrackingEnabled.value && !isSensorTrackingPaused.value) {
            captureLiveOrientationIntoManual()
            orientationTracker.isSensorTrackingPaused.value = true
            currentAz = manualPanAzimuth.toDouble()
            currentEl = manualPanElevation.toDouble()
        } else {
            currentAz = manualPanAzimuth.toDouble()
            currentEl = manualPanElevation.toDouble()
        }

        // 2. Launch smooth spherical S-curve animation
        animateCameraToCoordinates(currentAz, currentEl, targetAz, targetEl)
    }

    private fun animateCameraToCoordinates(startAz: Double, startEl: Double, endAz: Double, endEl: Double) {
        routingAnimationJob?.cancel()
        routingAnimationJob = viewModelScope.launch {
            // Find shortest path modulo 360 across the 0/360 boundary
            val azDiff = CelestialSearchIndex.getShortestAngularDistance(startAz.toFloat(), endAz.toFloat()).toDouble()
            val finalAz = startAz + azDiff

            val durationMs = 800L
            val startTime = System.currentTimeMillis()

            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                val fraction = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)

                // FastOutSlowIn S-curve for smooth acceleration/deceleration: 3t^2 - 2t^3
                val t = (fraction * fraction * (3f - 2f * fraction)).toDouble()

                val interpolatedAz = (startAz + (finalAz - startAz) * t) % 360.0
                val normalizedAz = if (interpolatedAz < 0.0) interpolatedAz + 360.0 else interpolatedAz
                val interpolatedEl = (startEl + (endEl - startEl) * t).coerceIn(-89.9, 89.9)

                manualPanAzimuth = normalizedAz.toFloat()
                manualPanElevation = interpolatedEl.toFloat()
                updateManualOrientation()

                if (fraction >= 1f) break
                delay(16L) // ~60 FPS tick
            }
        }
    }

    private fun createPlanetDetails(planet: PlanetState): CelestialBodyDetails {
        val app = getApplication<Application>()
        val name = skyNames().planet(planet.id, planet.nameEn)
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
     * Shows the disambiguation menu for multiple hit candidates.
     */
    fun showDisambiguation(hit: NightSkyRenderer.HitTarget.MultipleHits) {
        multipleHitsTargetState.value = hit
    }

    /**
     * Dismisses the disambiguation menu.
     */
    fun dismissDisambiguation() {
        multipleHitsTargetState.value = null
    }

    /**
     * Selects a candidate chosen from the disambiguation menu.
     */
    fun selectCandidate(candidate: NightSkyRenderer.CelestialCandidate) {
        dismissDisambiguation()
        when (candidate) {
            is NightSkyRenderer.CelestialCandidate.Planet -> selectPlanetById(candidate.planetId)
            is NightSkyRenderer.CelestialCandidate.Dso -> selectDsoByIndex(candidate.dsoIndex)
            is NightSkyRenderer.CelestialCandidate.MeteorShower -> selectMeteorByIndex(candidate.showerIndex)
            is NightSkyRenderer.CelestialCandidate.Star -> selectStarByIndex(candidate.starIndex)
            is NightSkyRenderer.CelestialCandidate.Constellation -> selectConstellationByIndex(candidate.constellationIndex)
        }
    }

    /**
     * Selects a dynamic planet or Moon by its ID string and populates [selectedBodyState].
     */
    fun selectPlanetById(planetId: String) {
        dismissDisambiguation()
        val planet = planetStates.value.firstOrNull { it.id.equals(planetId, ignoreCase = true) } ?: return
        selectedPlanetId.value = planet.id
        selectedStarIndex.value = -1
        selectedConstellationIndex.value = -1
        selectedDsoIndex.value = -1
        selectedMeteorIndex.value = -1
        _localScheduleDate.value = null
        selectedBodyState.value = createPlanetDetails(planet)
        fetchVisibilityForSelectedBody(planet.raDegrees, planet.decDegrees, isInitialSelection = true)
    }

    /**
     * Dismisses the celestial object selection.
     */
    fun clearSelectedBody() {
        visibilityCalculationJob?.cancel()
        visibilityCalculationJob = null
        _visibilityState.value = VisibilityState.Loading
        _localScheduleDate.value = null
        dismissDisambiguation()
        selectedStarIndex.value = -1
        selectedConstellationIndex.value = -1
        selectedDsoIndex.value = -1
        selectedMeteorIndex.value = -1
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
        dismissDisambiguation()
        routingAnimationJob?.cancel()
        routingAnimationJob = null

        val factor = fovDeg / viewWidth.coerceAtLeast(1f)

        if (isSensorTrackingEnabled.value && !isSensorTrackingPaused.value) {
            // State Injection on first touch drag: freeze live sensor orientation into manual pan state
            captureLiveOrientationIntoManual()
            orientationTracker.isSensorTrackingPaused.value = true
        }

        val deltaAzDeg = deltaX * factor
        val deltaElDeg = deltaY * factor

        val newAz = (manualPanAzimuth.toDouble() - deltaAzDeg + 360.0) % 360.0
        val newEl = (manualPanElevation.toDouble() + deltaElDeg).coerceIn(-89.9, 89.9)

        manualPanAzimuth = newAz.toFloat()
        manualPanElevation = newEl.toFloat()

        // The render loop reads the matrix in place; the published orientation is what the top bar
        // prints. Updating only the matrix left the bar on the pre-drag direction whenever the time
        // was virtual, because the per-second tick that also republishes it is off then. The HUD
        // recomposes only when a whole degree changes (WholeDegreesPolicy).
        updateManualOrientation()
    }

    fun adjustPan(deltaAzimuthDeg: Float, deltaElevationDeg: Float) {
        routingAnimationJob?.cancel()
        routingAnimationJob = null

        if (isSensorTrackingEnabled.value && !isSensorTrackingPaused.value) {
            captureLiveOrientationIntoManual()
            orientationTracker.isSensorTrackingPaused.value = true
        }

        val newAz = (manualPanAzimuth.toDouble() + deltaAzimuthDeg + 360.0) % 360.0
        val newEl = (manualPanElevation.toDouble() + deltaElevationDeg).coerceIn(-89.9, 89.9)

        manualPanAzimuth = newAz.toFloat()
        manualPanElevation = newEl.toFloat()

        // Matrix and top bar together, as in onPanGesture.
        updateManualOrientation()
    }

    /**
     * Nudges the standing calibration, leaving the sensors live. See [OrientationTracker.setCalibration].
     * It is an alignment, so it also locks the heading ([OrientationTracker.lockHeading]).
     */
    fun nudgeCalibration(deltaAzimuthDeg: Float, deltaElevationDeg: Float) {
        // The stars of a virtual place are not the ones above the phone: an alignment against them
        // would store a compass correction measured against nothing.
        if (observer.value.isVirtual) return
        orientationTracker.nudgeCalibrationBy(deltaAzimuthDeg, deltaElevationDeg)
        panAzimuthOffset.value = orientationTracker.calibrationAzimuthOffsetDeg
        panPitchOffset.value = orientationTracker.calibrationPitchOffsetDeg
        persistCalibration()
    }

    /** Keeps the pan the user just dragged as the standing calibration and resumes tracking. */
    fun commitCalibration() {
        if (observer.value.isVirtual) {
            // Under a virtual place a drag only looks around; nothing is kept as a calibration.
            startSensors()
            return
        }
        orientationTracker.commitPanAsCalibration()
        panAzimuthOffset.value = orientationTracker.calibrationAzimuthOffsetDeg
        panPitchOffset.value = orientationTracker.calibrationPitchOffsetDeg
        persistCalibration()
    }

    private fun persistCalibration() {
        val azimuth = panAzimuthOffset.value
        val pitch = panPitchOffset.value
        viewModelScope.launch { settingsStore.saveArCalibration(azimuth, pitch) }
    }

    fun adjustAzimuthOffset(deltaDeg: Float) {
        nudgeCalibration(deltaAzimuthDeg = deltaDeg, deltaElevationDeg = 0.0f)
    }

    fun adjustElevationOffset(deltaDeg: Float) {
        nudgeCalibration(deltaAzimuthDeg = 0.0f, deltaElevationDeg = deltaDeg)
    }

    fun resetAzimuthOffset() {
        resetCalibration()
    }

    fun resumeSensorTracking() {
        routingAnimationJob?.cancel()
        routingAnimationJob = null
        if (observer.value.isVirtual) {
            // Under a virtual place the bottom button only ends a pan: the stored compass correction
            // belongs to the real sky, and resetting it here would lose it for no reason.
            orientationTracker.resumeSensorTracking()
            panAzimuthOffset.value = orientationTracker.calibrationAzimuthOffsetDeg
            panPitchOffset.value = orientationTracker.calibrationPitchOffsetDeg
            return
        }
        orientationTracker.clearCalibration()
        orientationTracker.resumeSensorTracking()
        panAzimuthOffset.value = 0f
        panPitchOffset.value = 0f
        viewModelScope.launch { settingsStore.saveArCalibration(0f, 0f) }
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
            captureLiveOrientationIntoManual()
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

    /**
     * The bound camera's zoom control and the range its lens supports.
     *
     * Night Sky zooms far past any phone lens, so only the first few times are optical or digital on
     * the camera; the rest stays a zoom of the star field alone. Sending the camera the part it can
     * do keeps the image and the overlay together for as long as that is possible at all.
     */
    private var cameraControl: CameraControl? = null
    private var cameraZoomRange: ClosedFloatingPointRange<Float> = 1f..1f

    fun attachCamera(control: CameraControl?, minRatio: Float, maxRatio: Float) {
        cameraControl = control
        cameraZoomRange = if (maxRatio > minRatio && minRatio > 0f) minRatio..maxRatio else 1f..1f
        applyZoom(zoomRatioState.value)
    }

    private fun applyZoom(ratio: Float) {
        val clamped = ratio.coerceIn(MIN_ZOOM, MAX_SAFE_ZOOM)
        zoomRatioState.value = clamped
        val control = cameraControl
        if (control == null) {
            appliedCameraZoomState.value = 1.0f
            return
        }
        val onCamera = clamped.coerceIn(cameraZoomRange)
        appliedCameraZoomState.value = onCamera
        control.setZoomRatio(onCamera)
    }

    fun adjustZoomBy(factor: Float) {
        val current = zoomRatioState.value
        val updated = (current * factor).coerceIn(MIN_ZOOM, MAX_SAFE_ZOOM)
        if (kotlin.math.abs(updated - current) > 0.001f) {
            applyZoom(updated)
        }
    }

    fun setZoomRatio(ratio: Float) {
        applyZoom(ratio)
    }

    fun resetZoom() {
        applyZoom(1.0f)
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

    // Transient memory for Milky Way state restoration when Planetarium (opaque background) mode is active
    private var milkyWayStateBeforePlanetarium: Boolean = false

    fun toggleSkyBackgroundOpaque() {
        setSkyBackgroundOpaque(!isSkyBackgroundOpaque.value)
    }

    fun setSkyBackgroundOpaque(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                // Planetarium is turning ON:
                // 1. Memorize current Milky Way state
                milkyWayStateBeforePlanetarium = isMilkyWayVisible.value

                // 2. Force turn off Milky Way if it is currently ON
                if (isMilkyWayVisible.value) {
                    settingsStore.saveNightSkyMilkyWayVisible(false)
                }
            } else {
                // Planetarium is turning OFF:
                // Restore Milky Way if it was ON prior to entering Planetarium mode
                if (milkyWayStateBeforePlanetarium && !isMilkyWayVisible.value) {
                    settingsStore.saveNightSkyMilkyWayVisible(true)
                }
            }
            settingsStore.saveNightSkyOpaqueBackground(enabled)
        }
    }

    fun toggleMilkyWayVisible() {
        setMilkyWayVisible(!isMilkyWayVisible.value)
    }

    fun setMilkyWayVisible(enabled: Boolean) {
        viewModelScope.launch {
            if (isSkyBackgroundOpaque.value) {
                // If the user manually changes Milky Way while Planetarium is active,
                // update the memorized state so their explicit choice persists after exiting.
                milkyWayStateBeforePlanetarium = enabled
            }
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
            if (enabled && horizonSettings.value.realHorizonInNightSky) {
                val current = settingsStore.currentHorizon()
                settingsStore.saveHorizon(current.copy(realHorizonInNightSky = false))
            }
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

    fun toggleDsosVisible() {
        setDsosVisible(!isDsosVisible.value)
    }

    fun setDsosVisible(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.saveNightSkyDsosVisible(enabled)
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

    fun dragConstellationArtOpacity(opacity: Float) {
        constellationArtOpacityDrag.value = ConstellationArtOpacity.clamp(opacity)
    }

    fun commitConstellationArtOpacity() {
        val opacity = constellationArtOpacityDrag.value ?: return
        viewModelScope.launch {
            settingsStore.saveNightSkyConstellationArtOpacity(opacity)
            // Dropped only after the store holds the value, so the sky never jumps back for a frame.
            constellationArtOpacityDrag.value = null
        }
    }

    fun toggleAsterismNamesVisible() {
        setAsterismNamesVisible(!isAsterismNamesVisible.value)
    }

    fun setAsterismNamesVisible(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.saveNightSkyAsterismNamesVisible(enabled)
        }
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

    fun setDebugStarTapRadius(dp: Int) {
        val current = debugPreferences.value
        val clamped = dp.coerceIn(
            com.dazix.caelis.data.DebugPreferences.MIN_STAR_TAP_RADIUS_DP,
            com.dazix.caelis.data.DebugPreferences.MAX_STAR_TAP_RADIUS_DP,
        )
        viewModelScope.launch {
            settingsStore.saveDebugPreferences(current.copy(starTapRadiusDp = clamped))
        }
    }

    fun setShowTapRadiusIndicator(enabled: Boolean) {
        val current = debugPreferences.value
        viewModelScope.launch {
            settingsStore.saveDebugPreferences(current.copy(showTapRadiusIndicator = enabled))
        }
    }

    fun resetDebugFocusRegion() {
        debugFocusRadiusMultiplier.value = 0.48f
        debugFocusDisplayThreshold.value = 0.0f
        setDebugStarTapRadius(com.dazix.caelis.data.DebugPreferences.DEFAULT_STAR_TAP_RADIUS_DP)
        setShowTapRadiusIndicator(false)
    }
    fun startSensors() {
        routingAnimationJob?.cancel()
        routingAnimationJob = null
        orientationTracker.resumeSensorTracking()
        // Drop the pan, keep the calibration: returning to the screen must not silently undo the
        // alignment the user did last time.
        panAzimuthOffset.value = orientationTracker.calibrationAzimuthOffsetDeg
        panPitchOffset.value = orientationTracker.calibrationPitchOffsetDeg
        if (!isSensorTrackingEnabled.value) {
            setSensorTrackingEnabled(true)
        } else {
            orientationTracker.start()
        }
        locationProvider.startUpdates()
        // The terrain profile is one slot for the whole app and Sun AR may have filled it with another
        // place: the screen in front asks for its own again (stored, so instant on return).
        val obs = observer.value
        if (obs.canRequestProfile) {
            viewModelScope.launch {
                val cfg = settingsStore.currentHorizon()
                if (cfg.enabled && cfg.realHorizonInNightSky) horizonRepository.ensureProfile(obs.location, cfg)
            }
        }
    }

    // ==== Virtual places ====

    /** Shows [id] on this screen (and on Sun AR while the selection is synced); null is GPS. */
    fun selectPlace(id: String?) {
        viewModelScope.launch { placeRepository.select(PlaceScreen.NIGHT_SKY, id) }
    }

    fun savePlace(place: SavedPlace) {
        viewModelScope.launch { placeRepository.save(place) }
    }

    fun deletePlace(id: String) {
        viewModelScope.launch { placeRepository.delete(id) }
    }

    fun setPlaceSync(sync: Boolean) {
        viewModelScope.launch { placeRepository.setSync(sync, PlaceScreen.NIGHT_SKY) }
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
        /** Steps the alignment control nudges by: coarse enough to see, fine enough to land on. */
        const val COARSE_NUDGE_DEGREES = 0.5f
        const val FINE_NUDGE_DEGREES = 0.1f

        const val MIN_ZOOM = 0.4f
        const val MAX_SAFE_ZOOM = 2500.0f
    }
}
