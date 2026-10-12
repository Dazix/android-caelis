package com.dazix.caelis.ar.pose

import android.app.Activity
import android.content.Context
import com.dazix.caelis.BuildConfig
import com.dazix.caelis.data.SettingsStore
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where ARCore is allowed to start, and the only place it can.
 *
 * ## This is the only file in `app/src/main` that constructs a `com.google.ar.core.Session`
 *
 * ARCore is not a library that stays inside the app. It is a thin client for Google Play Services for
 * AR, which is a separate package, updated and run by Google, and which reports device identifiers,
 * usage and diagnostics to Google while a session runs (the Geospatial API additionally sends
 * camera-derived data and the location to Google's Visual Positioning Service). None of that can be
 * inspected or limited from here. What *can* be kept is the decision about whether it starts at all,
 * so the app's old promise - a build that is never told "yes" sends nothing anywhere - survives
 * having the dependency:
 *
 *  - [consent] is evaluated on **every** call that could start or keep a session alive, the same
 *    pattern as `DemTileDownloader`, never captured at construction. Revoking it in Settings takes
 *    effect at the next call, and [revalidateConsent] is there for the guard tick that must also stop
 *    a session that is already running.
 *  - `ArCoreApk.checkAvailability` is itself a call into Play Services, so it too waits for consent.
 *    Probing "is ARCore here?" to decide whether to show a switch would already be the very contact
 *    the switch is meant to gate; the Settings screen therefore never asks.
 *  - `requestInstall` opens Google Play. It is reachable only through
 *    [requestInstallFromUserAction], which a click handler calls, and never from a screen simply
 *    opening.
 *  - A consent read that *fails* is "no". The production reader maps every failure but cancellation to
 *    false, so an unreadable settings store can never be what starts a session.
 *
 * `NetworkIsolationTest` walks the sources and fails the build if a second file constructs a
 * `Session`, calls `requestInstall` or `checkAvailability`, or if this file stops consulting
 * [consent] in front of them.
 *
 * ## Threading
 *
 * Confined to the main thread, like `SkySurface`: `Session.resume` and `Session.pause` are not
 * thread-safe against each other, and the state below is plain fields. The only suspension points
 * are the consent reads and the availability retry delay, and each is followed by a generation
 * check so a [close] that raced it wins.
 *
 * @param consent whether the user agreed to ARCore. Checked per call.
 * @param geospatialConsent whether the user also agreed to the Geospatial API. Checked per call, and
 *   only ever honoured together with [consent].
 * @param geospatialApiKeyAvailable whether this build was given a Geospatial API key. Without one the
 *   Geospatial API is never requested, whatever the user said.
 */
class ArCoreSessionController(
    context: Context,
    private val consent: suspend () -> Boolean,
    private val geospatialConsent: suspend () -> Boolean,
    private val geospatialApiKeyAvailable: Boolean = BuildConfig.HAS_GEOSPATIAL_API_KEY,
    private val platform: ArCorePlatform = RealArCorePlatform,
    private val availabilityRetries: Int = AVAILABILITY_RETRIES,
    private val availabilityRetryDelayMs: Long = AVAILABILITY_RETRY_DELAY_MS,
) {

    private val appContext: Context = context.applicationContext ?: context

    /** What the controller currently knows. Contains nothing until something has been asked. */
    sealed interface Status {
        /** Nothing has been asked yet. */
        data object Idle : Status

        /** The user has not agreed (or the answer could not be read). ARCore was not contacted. */
        data object NotConsented : Status

        /** Play Services is still working out whether the device is supported. Ask again shortly. */
        data object Checking : Status

        /** Supported, but Google Play Services for AR is missing or too old: needs a user action. */
        data object NeedsInstall : Status

        /** This device, or this version of the app, cannot use ARCore. */
        data object Unsupported : Status

        /** Installed and usable; no session exists yet. */
        data object Ready : Status

        /** A session exists and is resumed. [geospatial] says whether the Geospatial API is on. */
        data class Running(val geospatial: Boolean) : Status

        data class Failed(val reason: Reason) : Status
    }

    enum class Reason { CAMERA_UNAVAILABLE, INSTALL_DECLINED, SESSION_REFUSED }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** The live session, for the pose source. Null whenever consent is absent, paused-and-closed or never given. */
    var session: Session? = null
        private set

    /** Whether the live session was configured with the Geospatial API on. */
    private var geospatialOn = false

    /** Bumped by [close], so a call that was suspended when the session was closed knows to give up. */
    private var generation = 0

    /**
     * Asks Play Services whether ARCore can run here. Does nothing - and does not contact Play
     * Services - until the user has agreed.
     */
    suspend fun checkAvailability(): Status {
        // The generation is taken *before* the consent read, and checked after every suspension:
        // a "yes" read just before the user withdrew it (revalidateConsent -> close) is stale by the
        // time it returns, and acting on it would contact Play Services for a closed controller.
        val startedIn = generation
        if (!consent()) return deny()
        if (generation != startedIn) return _status.value
        var availability = probe()
        var retriesLeft = availabilityRetries
        while (availability.isTransient && retriesLeft-- > 0) {
            // Google's own guidance: a transient answer means the check is still running, poll it.
            delay(availabilityRetryDelayMs)
            if (generation != startedIn) return _status.value
            // The user may have withdrawn consent while the check was still running.
            if (!consent()) return deny()
            if (generation != startedIn) return _status.value
            availability = probe()
        }
        return publish(
            when {
                availability == ArCoreApk.Availability.SUPPORTED_INSTALLED -> Status.Ready
                availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
                    availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> Status.NeedsInstall
                availability.isTransient -> Status.Checking
                availability.isUnsupported -> Status.Unsupported
                else -> Status.Failed(Reason.SESSION_REFUSED)
            },
        )
    }

    /**
     * Opens Google Play to install or update Google Play Services for AR. **Call this from a click
     * handler only.** Starting an install nobody asked for is the kind of surprise this app is built
     * not to have, and nothing in here can tell a click from a lifecycle callback, which is why
     * `NetworkIsolationTest` pins it to this method.
     */
    suspend fun requestInstallFromUserAction(activity: Activity): Status {
        val startedIn = generation
        if (!consent()) return deny()
        if (generation != startedIn) return _status.value
        installRequests.incrementAndGet()
        return try {
            when (platform.requestInstall(activity)) {
                ArCoreApk.InstallStatus.INSTALLED -> publish(Status.Ready)
                // Play was opened; the activity comes back to the foreground and checks again.
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> publish(Status.NeedsInstall)
            }
        } catch (_: UnavailableUserDeclinedInstallationException) {
            publish(Status.Failed(Reason.INSTALL_DECLINED))
        } catch (_: UnavailableDeviceNotCompatibleException) {
            publish(Status.Unsupported)
        }
    }

    /**
     * Creates the session if there is none, configures it and resumes it.
     *
     * Both consents are read here, again, even if [checkAvailability] ran a moment ago: the answer
     * has to be the one the user has *now*. Returns the resulting status and never throws for an
     * ARCore refusal.
     */
    suspend fun resume(): Status {
        // Taken before the first read; see checkAvailability. Every suspension below is followed by
        // a generation check, and the last one sits directly in front of the construction: a resume
        // suspended in its final consent read while the user withdrew consent (or the screen closed
        // the controller) used to go on and create a session nobody wanted any more.
        val startedIn = generation
        if (!consent()) return deny()
        if (generation != startedIn) return _status.value

        var live = session
        if (live == null) {
            val available = checkAvailability()
            if (available != Status.Ready) return available
            if (generation != startedIn) return _status.value

            // Read once more directly in front of the construction: this is the line that makes
            // Google Play Services run, so it gets its own answer rather than one a retry ago.
            if (!consent()) return deny()
            if (generation != startedIn) return _status.value
            val wantsGeospatial = geospatialApiKeyAvailable && geospatialConsent()
            if (generation != startedIn) return _status.value
            if (!consent()) return deny()
            if (generation != startedIn) return _status.value

            live = try {
                sessionsCreated.incrementAndGet()
                platform.createSession(appContext)
            } catch (_: UnavailableDeviceNotCompatibleException) {
                return publish(Status.Unsupported)
            } catch (_: UnavailableSdkTooOldException) {
                // The installed ARCore is newer than this app's SDK; updating the app is the fix.
                return publish(Status.Unsupported)
            } catch (_: UnavailableException) {
                return publish(Status.NeedsInstall)
            }
            session = live
            geospatialOn = configure(live, wantsGeospatial) ?: run {
                close()
                return publish(Status.Failed(Reason.SESSION_REFUSED))
            }
        }

        return try {
            live.resume()
            publish(Status.Running(geospatialOn))
        } catch (_: CameraNotAvailableException) {
            publish(Status.Failed(Reason.CAMERA_UNAVAILABLE))
        } catch (_: SecurityException) {
            // The camera permission was withdrawn between the check and the resume.
            publish(Status.Failed(Reason.CAMERA_UNAVAILABLE))
        }
    }

    /** Stops the camera and tracking but keeps the session, so a quick return does not pay a restart. */
    fun pause() {
        val live = session ?: return
        live.pause()
        if (_status.value is Status.Running) _status.value = Status.Ready
    }

    /**
     * Pauses and releases the session. Idempotent. Everything that ends a screen's use of ARCore
     * should arrive here, because an open session keeps Play Services' camera pipeline alive.
     */
    fun close() {
        generation++
        val live = session
        session = null
        geospatialOn = false
        if (live != null) {
            runCatching { live.pause() }
            runCatching { live.close() }
        }
        if (_status.value is Status.Running) _status.value = Status.Ready
    }

    /**
     * [checkAvailability] under the name the pose rig uses: asked *before* the camera changes hands, so
     * that a phone that cannot run ARCore (or lacks Google Play Services for AR) is found out while
     * CameraX still has the camera, instead of by a failed start that costs the picture a rebind.
     * Same consent rule: nothing is asked without it.
     */
    suspend fun probeAvailability(): Status = checkAvailability()

    /**
     * For the guard tick: confirms the user still agrees and, if not, closes the session at once.
     * [resume] cannot do this, because it is not called while a session is happily running.
     */
    suspend fun revalidateConsent(): Boolean {
        if (consent()) return true
        deny()
        return false
    }

    private fun probe(): ArCoreApk.Availability {
        availabilityChecks.incrementAndGet()
        return platform.checkAvailability(appContext)
    }

    private fun deny(): Status {
        close()
        return publish(Status.NotConsented)
    }

    private fun publish(status: Status): Status {
        _status.value = status
        return status
    }

    /**
     * The one configuration this app ever asks ARCore for: a 6-DoF pose and the camera image, and
     * nothing else. Every optional ARCore feature is a feature that processes the camera for Google's
     * component, so each is explicitly off rather than left to a default that may change.
     *
     * Returns whether the Geospatial API ended up on, or null if ARCore refused even the plain
     * configuration.
     */
    private fun configure(session: Session, wantsGeospatial: Boolean): Boolean? {
        if (wantsGeospatial && session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
            // Fine location may have been withheld, which ARCore reports by refusing the
            // configuration. The pose does not need Geospatial, so fall through to the plain one.
            if (tryConfigure(session, geospatial = true)) return true
        }
        return if (tryConfigure(session, geospatial = false)) false else null
    }

    private fun tryConfigure(session: Session, geospatial: Boolean): Boolean {
        // Explicit calls rather than property syntax: Config's setters return `this`, which Kotlin
        // does not treat as a synthetic property setter.
        val config = Config(session)
            .setPlaneFindingMode(Config.PlaneFindingMode.DISABLED)
            .setLightEstimationMode(Config.LightEstimationMode.DISABLED)
            .setDepthMode(Config.DepthMode.DISABLED)
            .setSemanticMode(Config.SemanticMode.DISABLED)
            .setCloudAnchorMode(Config.CloudAnchorMode.DISABLED)
            .setInstantPlacementMode(Config.InstantPlacementMode.DISABLED)
            // The overlay is composited with the camera image, so the pose must be the one the image
            // was taken at. ARCore's electronic stabilisation would shift the image against it (the
            // same reason EIS and OIS are forced off on the CameraX path).
            .setImageStabilizationMode(Config.ImageStabilizationMode.OFF)
            .setFocusMode(Config.FocusMode.AUTO)
            // LATEST_CAMERA_IMAGE: update() returns the newest frame instead of blocking until the
            // next one, so the render loop never waits on the camera.
            .setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
            // The image reaches Filament as a HardwareBuffer through the same ACQUIRED stream the
            // CameraX path uses, so ARCore never needs a GL texture or a GL context of its own.
            .setTextureUpdateMode(Config.TextureUpdateMode.EXPOSE_HARDWARE_BUFFER)
            .setGeospatialMode(
                if (geospatial) Config.GeospatialMode.ENABLED else Config.GeospatialMode.DISABLED,
            )
        return try {
            session.configure(config)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /** Google recommends polling a transient availability about every 200 ms. */
        const val AVAILABILITY_RETRY_DELAY_MS = 200L
        const val AVAILABILITY_RETRIES = 10

        private val sessionsCreated = AtomicInteger()
        private val availabilityChecks = AtomicInteger()
        private val installRequests = AtomicInteger()

        /**
         * How many times any controller in this process has *tried* to construct a `Session` - counted
         * in front of the attempt, so a refusal still counts. `ArCoreConsentDeviceTest` holds this at
         * zero while consent is off.
         */
        val sessionCreationAttempts: Int get() = sessionsCreated.get()

        /** How many times Play Services was asked whether ARCore is available. Zero without consent. */
        val availabilityCheckCount: Int get() = availabilityChecks.get()

        /** How many times Google Play was asked to install ARCore. Zero without a user action. */
        val installRequestCount: Int get() = installRequests.get()

        fun resetCountersForTest() {
            sessionsCreated.set(0)
            availabilityChecks.set(0)
            installRequests.set(0)
        }

        /**
         * The production wiring: consent comes from the settings store, read fresh on every call.
         *
         * Any failure to read, other than cancellation, is "no": a controller that guessed "yes" when
         * the store was unreadable would start Google's component without an answer from the user.
         */
        fun fromSettings(
            context: Context,
            settings: SettingsStore,
            geospatialApiKeyAvailable: Boolean = BuildConfig.HAS_GEOSPATIAL_API_KEY,
        ): ArCoreSessionController = ArCoreSessionController(
            context = context,
            consent = { readOrDeny { settings.currentArCore().arCoreConsent } },
            geospatialConsent = { readOrDeny { settings.currentArCore().geospatialConsent } },
            geospatialApiKeyAvailable = geospatialApiKeyAvailable,
        )
    }
}

/**
 * A consent read in which anything that goes wrong means "no". Cancellation is not a failure and is
 * rethrown, so a cancelled caller is not told it was refused.
 */
internal suspend fun readOrDeny(read: suspend () -> Boolean): Boolean = try {
    read()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}

/**
 * The calls that actually reach Google Play Services for AR, behind one seam so the consent logic
 * above can be driven by a test without a Google component on the machine. Implemented for real by
 * [RealArCorePlatform], which lives in this file because it holds the `Session(` constructor.
 */
interface ArCorePlatform {
    fun checkAvailability(context: Context): ArCoreApk.Availability

    /** Always called with `userRequestedInstall = true`: it only runs from a click. */
    fun requestInstall(activity: Activity): ArCoreApk.InstallStatus

    fun createSession(context: Context): Session
}

internal object RealArCorePlatform : ArCorePlatform {
    override fun checkAvailability(context: Context): ArCoreApk.Availability =
        ArCoreApk.getInstance().checkAvailability(context)

    override fun requestInstall(activity: Activity): ArCoreApk.InstallStatus =
        ArCoreApk.getInstance().requestInstall(activity, true)

    override fun createSession(context: Context): Session = Session(context)
}
