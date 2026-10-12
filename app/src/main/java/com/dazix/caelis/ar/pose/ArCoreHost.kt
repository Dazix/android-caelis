package com.dazix.caelis.ar.pose

import android.content.Context
import com.dazix.caelis.data.SettingsStore

/**
 * ARCore as the pose rig sees it: something that can be started (after consent), paused, closed and
 * read frames from. No ARCore types, so the rig and its tests do not need ARCore at all.
 */
interface ArCoreHost {

    /** The newest-frame reader. Reports nothing while no session is running. */
    val frames: ArFrameSource

    /**
     * Whether ARCore could be started here, asked while CameraX still owns the camera so that a phone
     * that cannot run it never loses its picture to a failed attempt. Consent is checked inside.
     */
    suspend fun availability(): ArAvailability

    /**
     * Creates the session if there is none, configures it and resumes it. Consent is checked inside,
     * per call. True when a session is running.
     */
    suspend fun start(): Boolean

    /** Stops the camera and tracking but keeps the session. */
    fun pause()

    /** Releases the session. Idempotent. */
    fun close()

    /** Confirms the user still agrees, closing the session at once if not. */
    suspend fun revalidateConsent(): Boolean
}

/** What [ArCoreHost.availability] found. */
enum class ArAvailability {
    /** Installed and supported: the camera may be handed over. */
    USABLE,

    /** Not known yet (the check is still running, or consent was withdrawn meanwhile): ask again later. */
    TRANSIENT,

    /** Not supported, not installed, or refused: do not ask again until the user flips consent. */
    UNAVAILABLE,
}

/** The production host: [ArCoreSessionController] plus the frame reader over its session. */
class ControllerArCoreHost(
    private val controller: ArCoreSessionController,
    private val source: ArCoreFrameSource = ArCoreFrameSource(controller),
) : ArCoreHost {

    override val frames: ArFrameSource get() = source

    override suspend fun availability(): ArAvailability = when (controller.probeAvailability()) {
        is ArCoreSessionController.Status.Ready, is ArCoreSessionController.Status.Running -> ArAvailability.USABLE
        is ArCoreSessionController.Status.Idle,
        is ArCoreSessionController.Status.Checking,
        is ArCoreSessionController.Status.NotConsented,
        -> ArAvailability.TRANSIENT
        is ArCoreSessionController.Status.NeedsInstall,
        is ArCoreSessionController.Status.Unsupported,
        is ArCoreSessionController.Status.Failed,
        -> ArAvailability.UNAVAILABLE
    }

    override suspend fun start(): Boolean {
        val status = controller.resume()
        if (status !is ArCoreSessionController.Status.Running) return false
        // Even a resumed session may have a different world than before: new epoch, new alignment.
        source.onSessionStarted()
        return true
    }

    override fun pause() {
        controller.pause()
        source.onSessionEnded()
    }

    override fun close() {
        controller.close()
        source.onSessionEnded()
    }

    override suspend fun revalidateConsent(): Boolean {
        val agreed = controller.revalidateConsent()
        if (!agreed) source.onSessionEnded()
        return agreed
    }

    companion object {
        /** The production wiring: consent read from the settings store on every call. */
        fun fromSettings(context: Context, settings: SettingsStore): ControllerArCoreHost =
            ControllerArCoreHost(ArCoreSessionController.fromSettings(context, settings))
    }
}
