package com.dazix.caelis.ui.solar

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dazix.caelis.ar.CameraFovCalculator
import com.dazix.caelis.ar.CameraFrameGeometry
import com.dazix.caelis.ar.DeviceOrientation
import com.dazix.caelis.ar.FovResult
import com.dazix.caelis.ar.FrameGrabber
import com.dazix.caelis.ar.OrientationTracker
import com.dazix.caelis.ar.SkylineAligner
import com.dazix.caelis.ar.SolarLocationProvider
import com.dazix.caelis.ar.pose.SensorPose
import com.dazix.caelis.ar.pose.SensorPoseSource
import com.dazix.caelis.data.HorizonSettings
import com.dazix.caelis.data.SettingsStore
import com.dazix.caelis.data.horizon.DemTileImporter
import com.dazix.caelis.data.horizon.HorizonRepository
import com.dazix.caelis.data.horizon.HorizonState
import com.dazix.caelis.domain.arframe.LatestRequestGate
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.solar.BubbleInterval
import com.dazix.caelis.domain.solar.GeoLocation
import com.dazix.caelis.domain.solar.LunarCoordinates
import com.dazix.caelis.domain.solar.LunarPositionCalculator
import com.dazix.caelis.domain.solar.LunarTrajectory
import com.dazix.caelis.domain.solar.LunarTrajectoryPlanner
import com.dazix.caelis.domain.solar.SolarCoordinates
import com.dazix.caelis.domain.solar.SolarPositionCalculator
import com.dazix.caelis.domain.solar.SolarTrajectory
import com.dazix.caelis.domain.solar.SolarTrajectoryPlanner
import com.dazix.caelis.domain.solar.TerrainEvent
import com.dazix.caelis.domain.solar.TerrainRiseSetCalculator
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * When the Sun actually clears, and is lost behind, the real terrain — as opposed to the flat-Earth
 * times in [SolarTrajectory].
 *
 * [hiddenAllDay] is its own field rather than "both times are null", because a north-facing gorge in
 * winter is a real answer worth saying out loud, and it looks exactly like a failed computation
 * otherwise.
 */
data class TerrainTimes(
    val sunrise: TerrainEvent? = null,
    val sunset: TerrainEvent? = null,
    val hiddenAllDay: Boolean = false,
) {
    val hasAny: Boolean get() = sunrise != null || sunset != null || hiddenAllDay
}

/**
 * What came of pressing "align automatically".
 *
 * Every unsuccessful case is its own value rather than a bare null, because the reason is the useful
 * part: "point at a skyline" and "switch the terrain layer on" ask for different things from a user
 * standing in a field.
 */
sealed interface AutoAlignResult {
    /** A frame has been asked for and is being matched. */
    data object Working : AutoAlignResult

    /** No terrain profile yet, so there is nothing to align against. */
    data object NoProfile : AutoAlignResult

    /** The camera is not bound — no permission, or it failed to open. */
    data object NoCamera : AutoAlignResult

    /** Nothing in the frame looked like a skyline: fog, darkness, a wall, a fit that did not stand out. */
    data object NoSkyline : AutoAlignResult

    /**
     * The calibration has been moved onto the skyline.
     *
     * @param azimuthDetermined false when the terrain in view was too flat to pin the compass, so
     *   only the vertical half of the correction was applied.
     */
    data class Aligned(val residualDegrees: Double, val azimuthDetermined: Boolean) : AutoAlignResult
}

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
    val cameraFov: FovResult = FovResult(
        horizontalDegrees = CameraFovCalculator.DEFAULT_PORTRAIT_FOV_DEGREES,
        isDynamicStreamCalculated = false,
    ),
    val horizonSettings: HorizonSettings = HorizonSettings(),
    /** `null` until a profile exists for where the observer is standing. */
    val horizonProfile: HorizonProfile? = null,
    val horizonState: HorizonState = HorizonState.Idle,
    /** False in a build made from source with the `INTERNET` line removed from the manifest. */
    val canDownloadTerrain: Boolean = true,
    val terrainTimes: TerrainTimes = TerrainTimes(),
    val showConsentDialog: Boolean = false,
    val debugPreferences: com.dazix.caelis.data.DebugPreferences = com.dazix.caelis.data.DebugPreferences(),
    val effectiveRangeKm: Int? = null,
    val activeTileCount: Int = 0,
) {
    val cameraFovHorizontal: Float get() = cameraFov.horizontalDegrees
    val isDynamicStreamCalculated: Boolean get() = cameraFov.isDynamicStreamCalculated
    /** Draw the terrain layer only when it is switched on *and* there is something to draw. */
    val isHorizonVisible: Boolean get() = horizonSettings.enabled && horizonProfile != null
}

@OptIn(FlowPreview::class)
class SunArViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val orientationTracker = OrientationTracker(application)
    private val locationProvider = SolarLocationProvider(application)
    private val horizonRepository = HorizonRepository.getInstance(application)

    val horizonSettings: StateFlow<HorizonSettings> = settingsStore.horizon.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        HorizonSettings(),
    )

    val horizonProfile: StateFlow<HorizonProfile?> = horizonRepository.profile
    val horizonState: StateFlow<HorizonState> = horizonRepository.state
    val effectiveRangeKm: StateFlow<Int?> = horizonRepository.effectiveRangeKm
    val activeTileCount: StateFlow<Int> = horizonRepository.activeTileCount
    val debugPreferences: StateFlow<com.dazix.caelis.data.DebugPreferences> = settingsStore.debugPreferences.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        com.dazix.caelis.data.DebugPreferences(),
    )

    /** Whether this build declares `INTERNET` at all. Constant for the life of the process. */
    val canDownloadTerrain: Boolean = horizonRepository.canDownload

    private val selectedDateState = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = selectedDateState.asStateFlow()

    private val bubbleIntervalState = MutableStateFlow(BubbleInterval.ONE_HOUR)
    val bubbleInterval: StateFlow<BubbleInterval> = bubbleIntervalState.asStateFlow()

    /**
     * The field of view the camera is actually delivering.
     *
     * There is no zoom on this screen, so this is simply what [CameraPreview] measured — no division
     * by a zoom ratio, and no way for the image and the overlay to end up at different scales.
     */
    private val baseCameraFovState = MutableStateFlow(
        FovResult(
            horizontalDegrees = CameraFovCalculator.DEFAULT_PORTRAIT_FOV_DEGREES,
            isDynamicStreamCalculated = false,
        ),
    )
    val cameraFov: StateFlow<FovResult> = baseCameraFovState.asStateFlow()
    val cameraFovHorizontal: StateFlow<Float> = baseCameraFovState.map { it.horizontalDegrees }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        CameraFovCalculator.DEFAULT_PORTRAIT_FOV_DEGREES,
    )

    val orientation: StateFlow<DeviceOrientation?> = orientationTracker.orientation

    /** The sensor tracker as a pose source, for the screen's pose rig ([com.dazix.caelis.ar.pose.SkyPoseRig]). */
    val sensorPose: SensorPose = SensorPoseSource(orientationTracker)

    /** The frame loop's pose, at the camera image's instant [timestampNs] (0 = live). Allocation-free. */
    fun getRenderMatrix(timestampNs: Long, out: FloatArray) = orientationTracker.getRenderMatrix(timestampNs, out)
    val headingState: StateFlow<com.dazix.caelis.domain.arframe.HeadingState> = orientationTracker.headingState
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
    /**
     * Whether a standing correction is in force.
     *
     * No longer "are the sensors paused": on this screen they never are. Dragging calibrates and
     * keeps tracking, so the only thing that makes the overlay non-default is a non-zero offset.
     */
    val isCalibrated: StateFlow<Boolean> = combine(panAzimuthOffset, panPitchOffset) { az, pitch ->
        az != 0f || pitch != 0f
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

    /**
     * The terrain crossing times, recomputed whenever the day, the place or the skyline changes.
     *
     * Kept out of the per-frame path deliberately: this walks 720 trajectory samples and bisects
     * each crossing, which is far too much to do on a canvas redraw but nothing at all once a minute.
     */
    val terrainTimes: StateFlow<TerrainTimes> = combine(
        trajectory,
        horizonProfile,
        horizonSettings,
    ) { traj, prof, cfg ->
        if (prof == null || !cfg.enabled) {
            TerrainTimes()
        } else {
            TerrainTimes(
                sunrise = TerrainRiseSetCalculator.terrainSunrise(traj, prof, ZoneId.systemDefault()),
                sunset = TerrainRiseSetCalculator.terrainSunset(traj, prof, ZoneId.systemDefault()),
                hiddenAllDay = TerrainRiseSetCalculator.isSunHiddenAllDay(traj, prof),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TerrainTimes())

    private val showConsentDialogState = MutableStateFlow(false)

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
        cameraFov,
        horizonSettings,
        horizonProfile,
        horizonState,
        terrainTimes,
        showConsentDialogState,
        debugPreferences,
        effectiveRangeKm,
        activeTileCount,
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
        val fov = args[10] as FovResult
        val horizonCfg = args[11] as HorizonSettings
        val horizonProf = args[12] as HorizonProfile?
        val horizonSt = args[13] as HorizonState
        val terrain = args[14] as TerrainTimes
        val showConsent = args[15] as Boolean
        val debugPrefs = args[16] as com.dazix.caelis.data.DebugPreferences
        val effRange = args[17] as Int?
        val actTiles = args[18] as Int

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
            cameraFov = fov,
            horizonSettings = horizonCfg,
            horizonProfile = horizonProf,
            horizonState = horizonSt,
            canDownloadTerrain = canDownloadTerrain,
            terrainTimes = terrain,
            showConsentDialog = showConsent,
            debugPreferences = debugPrefs,
            effectiveRangeKm = effRange,
            activeTileCount = actTiles,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SunArUiState())

    init {
        // Restore the one compass correction this phone has.
        viewModelScope.launch {
            val initialAz = settingsStore.arAzimuthOffset.firstOrNull() ?: 0f
            val initialPitch = settingsStore.arElevationOffset.firstOrNull() ?: 0f
            panAzimuthOffset.value = initialAz
            panPitchOffset.value = initialPitch
            // setCalibration, never setPanOffsets: the latter pauses the sensors, so restoring a
            // saved calibration used to open this screen with the overlay frozen in place.
            orientationTracker.setCalibration(initialAz, initialPitch)
        }

        // Debounced because a drag emits on every frame and each write would hit the disk.
        viewModelScope.launch {
            combine(panAzimuthOffset, panPitchOffset) { az, pitch -> Pair(az, pitch) }
                .drop(1)
                .debounce(300L)
                .collect { (az, pitch) -> settingsStore.saveArCalibration(az, pitch) }
        }

        // Update magnetic declination whenever location updates
        viewModelScope.launch {
            locationProvider.location.collect { loc ->
                orientationTracker.updateLocation(loc)
            }
        }

        // Show consent dialog immediately if terrain is enabled but consent not answered
        viewModelScope.launch {
            horizonSettings.collect { cfg ->
                if (cfg.enabled && !cfg.networkConsentAnswered && canDownloadTerrain) {
                    showConsentDialogState.value = true
                }
            }
        }

        // Keep a terrain profile for wherever the observer currently is.
        //
        // Debounced because the location provider emits every few seconds while the GPS settles, and
        // each emission would otherwise be a candidate for a 26 MB download. The repository rounds
        // the position to about 110 m and ignores a repeat of the same request, so a stationary
        // observer resolves this exactly once.
        viewModelScope.launch {
            combine(locationProvider.location, locationProvider.hasGpsFix, horizonSettings) { loc, hasFix, cfg ->
                Triple(loc, hasFix, cfg)
            }
                .debounce(1_000L)
                .collect { (loc, hasFix, cfg) ->
                    if (hasFix) {
                        horizonRepository.ensureProfile(loc, cfg)
                    }
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

    fun setCameraFov(fovResult: FovResult) {
        if (baseCameraFovState.value != fovResult) {
            baseCameraFovState.value = fovResult
        }
    }

    fun setCameraFov(fovDeg: Float, includesZoom: Boolean = false) {
        setCameraFov(FovResult(horizontalDegrees = fovDeg, isDynamicStreamCalculated = includesZoom))
    }

    /**
     * Dragging a finger across the viewfinder walks the overlay onto the real skyline.
     *
     * It is a calibration, not a pan: the sensors keep running, so letting go snaps nothing back and
     * turning around still works. The pixels-to-degrees factor and the sign are the ones the pan
     * gesture used, so the gesture feels exactly as it did.
     */
    fun onPanGesture(deltaX: Float, deltaY: Float, viewWidth: Float, fovDeg: Float) {
        val factor = fovDeg / viewWidth.coerceAtLeast(1f)
        nudgeCalibration(
            deltaAzimuthDeg = -deltaX * factor,
            deltaElevationDeg = deltaY * factor,
        )
    }

    /**
     * Nudges the standing calibration, leaving the sensors live.
     *
     * This is what both the drag and the alignment panel drive: the user walks the silhouette onto
     * the real ridge while the overlay keeps following the phone.
     */
    fun nudgeCalibration(deltaAzimuthDeg: Float, deltaElevationDeg: Float) {
        orientationTracker.nudgeCalibrationBy(deltaAzimuthDeg, deltaElevationDeg)
        panAzimuthOffset.value = orientationTracker.calibrationAzimuthOffsetDeg
        panPitchOffset.value = orientationTracker.calibrationPitchOffsetDeg
    }

    /** Set by [com.dazix.caelis.ar.CameraPreview] once bound; null when the camera is unavailable. */
    private var frameGrabber: FrameGrabber? = null

    fun attachFrameGrabber(grabber: FrameGrabber?) {
        frameGrabber = grabber
    }

    private val autoAlignState = MutableStateFlow<AutoAlignResult?>(null)
    private val autoAlignGate = LatestRequestGate()
    private var autoAlignTimeout: Job? = null

    /** The outcome of the last automatic alignment, for the UI to report. Cleared when consumed. */
    val autoAlign: StateFlow<AutoAlignResult?> = autoAlignState.asStateFlow()

    fun consumeAutoAlignResult() {
        autoAlignState.value = null
    }

    /**
     * Matches the camera image against the terrain profile and applies the offset it finds.
     *
     * Refuses rather than guesses. A wrong alignment is worse than none because it looks measured:
     * the user has no way to tell a fit to a cloud bank from a fit to a ridge, so the decision is
     * made here on the residual and reported either way.
     */
    fun autoAlign(viewportWidth: Float, viewportHeight: Float, displayRotation: Int) {
        // Whatever was still outstanding is abandoned first, also when this call is refused below:
        // an old timer or a late frame must not speak for a request that is no longer the one shown.
        autoAlignTimeout?.cancel()
        autoAlignGate.invalidate()
        val grabber = frameGrabber
        val profile = horizonProfile.value
        when {
            profile == null || !horizonSettings.value.enabled -> {
                autoAlignState.value = AutoAlignResult.NoProfile
                return
            }
            grabber == null -> {
                autoAlignState.value = AutoAlignResult.NoCamera
                return
            }
        }

        autoAlignState.value = AutoAlignResult.Working
        // A camera that was handed over (ARCore <-> CameraX) while the frame was being awaited takes
        // the request with it; without this the screen would say "working" for ever.
        // The timer belongs to this request alone (token) and ends with its frame: a bare "is it still
        // Working?" check let an earlier timer fail a later request after a fraction of its window.
        val request = autoAlignGate.begin()
        autoAlignTimeout = viewModelScope.launch {
            delay(AUTO_ALIGN_FRAME_TIMEOUT_MS)
            if (autoAlignGate.finish(request)) autoAlignState.value = AutoAlignResult.NoCamera
        }
        grabber.grabNext { frame ->
            // The camera may call back from its own thread; the gate and the timer are main-thread state.
            viewModelScope.launch {
                // A frame that arrives after the timeout, or for a request that was replaced, is not ours.
                if (!autoAlignGate.isCurrent(request)) return@launch
                autoAlignTimeout?.cancel()
                val arPose = frame.pose
                val geometry = if (arPose != null) {
                    // ARCore frame: the Y plane, the pose the overlay was drawn with for *that* frame
                    // (nothing to interpolate) and the field of view and image-to-screen scale as
                    // ARCore maps this very image onto the viewport.
                    CameraFrameGeometry(
                        frameWidth = frame.width,
                        frameHeight = frame.height,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                        fillScale = arPose.fillScale,
                        rotationMatrix = arPose.rotationMatrix,
                        displayRotation = displayRotation,
                        fovHorizontalDegrees = arPose.fovHorizontalDegrees,
                    )
                } else {
                    // Sensor path: CameraX luma, and the rotation-vector pose SLERPed to that frame's
                    // timestamp.
                    val syncMatrix = FloatArray(16)
                    orientationTracker.getSynchronizedMatrix(frame.timestampNs, syncMatrix)
                    CameraFrameGeometry.fillCenter(
                        frameWidth = frame.width,
                        frameHeight = frame.height,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                        rotationMatrix = syncMatrix,
                        displayRotation = displayRotation,
                        fovHorizontalDegrees = cameraFovHorizontal.value,
                    )
                }
                val match = withContext(Dispatchers.Default) {
                    SkylineAligner.align(frame = frame, geometry = geometry, profile = profile)
                }
                // The user may have asked again, or reset, while the fit ran off the main thread.
                if (!autoAlignGate.finish(request)) return@launch
                if (match == null) {
                    autoAlignState.value = AutoAlignResult.NoSkyline
                    return@launch
                }
                nudgeCalibration(
                    deltaAzimuthDeg = match.azimuthOffsetDegrees.toFloat(),
                    deltaElevationDeg = match.elevationOffsetDegrees.toFloat(),
                )
                // A determined fit is a measured heading even when its correction rounds to zero.
                if (match.azimuthDetermined) orientationTracker.lockHeading()
                autoAlignState.value = AutoAlignResult.Aligned(
                    residualDegrees = match.residualDegrees,
                    azimuthDetermined = match.azimuthDetermined,
                )
            }
        }
    }

    /** Keeps the pan the user just dragged as the standing calibration and resumes tracking. */
    fun commitCalibration() {
        orientationTracker.commitPanAsCalibration()
        panAzimuthOffset.value = orientationTracker.calibrationAzimuthOffsetDeg
        panPitchOffset.value = orientationTracker.calibrationPitchOffsetDeg
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
        orientationTracker.clearCalibration()
        orientationTracker.resumeSensorTracking()
        panAzimuthOffset.value = 0f
        panPitchOffset.value = 0f
        viewModelScope.launch {
            settingsStore.saveArCalibration(0f, 0f)
        }
    }

    fun resetCalibration() {
        resumeSensorTracking()
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

    // ==== Terrain horizon ====
    //
    // Only the two things this screen owns. Everything shared — search radius, eye height, the
    // download, the tile cache — is configured once in the app's Settings and reached through
    // MainViewModel, because it describes where you are standing rather than which screen is open.

    fun requestToggleHorizon() {
        val current = horizonSettings.value
        if (!current.enabled && !current.networkConsentAnswered && canDownloadTerrain) {
            showConsentDialogState.value = true
        } else {
            toggleHorizon()
        }
    }

    fun dismissConsentDialog() {
        showConsentDialogState.value = false
    }

    /**
     * Records the user's answer to the network question and, on a yes, switches the layer on.
     *
     * A "no" is stored just as durably as a yes: the prompt must not come back on the next launch.
     * It leaves the layer itself alone, because the hand-import path still works without a network.
     */
    fun answerNetworkConsent(granted: Boolean, wifiOnly: Boolean? = null) {
        showConsentDialogState.value = false
        viewModelScope.launch {
            val current = settingsStore.currentHorizon()
            val updated = current.copy(
                networkConsent = granted,
                enabled = true,
                wifiOnly = if (granted && wifiOnly != null) wifiOnly else current.wifiOnly,
            )
            settingsStore.saveHorizon(updated)
            if (granted) {
                if (locationProvider.hasGpsFix.value) {
                    horizonRepository.ensureProfile(locationProvider.location.value, updated)
                } else {
                    horizonRepository.setLocating()
                    val hasFix = locationProvider.awaitGpsFix()
                    if (hasFix) {
                        horizonRepository.ensureProfile(locationProvider.location.value, updated)
                    } else {
                        horizonRepository.setLocationFailed()
                    }
                }
            }
        }
    }

    /** Show or hide the terrain silhouette. The only horizon control on this screen. */
    fun toggleHorizon() {
        viewModelScope.launch {
            val current = settingsStore.currentHorizon()
            val updated = current.copy(enabled = !current.enabled)
            settingsStore.saveHorizon(updated)
            if (updated.enabled && updated.networkDownloadAllowed) {
                if (locationProvider.hasGpsFix.value) {
                    horizonRepository.ensureProfile(locationProvider.location.value, updated)
                } else {
                    horizonRepository.setLocating()
                    val hasFix = locationProvider.awaitGpsFix()
                    if (hasFix) {
                        horizonRepository.ensureProfile(locationProvider.location.value, updated)
                    } else {
                        horizonRepository.setLocationFailed()
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSensors()
    }

    companion object {
        /** Steps the alignment panel nudges by: coarse enough to see, fine enough to land on. */
        const val COARSE_NUDGE_DEGREES = 0.5f
        const val FINE_NUDGE_DEGREES = 0.1f

        /** How long an automatic alignment waits for the camera to hand over its frame. */
        const val AUTO_ALIGN_FRAME_TIMEOUT_MS = 4_000L
    }
}
