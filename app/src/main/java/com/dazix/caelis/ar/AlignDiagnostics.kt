package com.dazix.caelis.ar

import android.content.Context
import android.util.Log
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.horizon.SkylineFit
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug builds only: keeps what an automatic alignment saw, so a refusal or a wrong answer on the
 * device can be replayed on the JVM instead of guessed at.
 *
 * Each attempt is one directory under `filesDir/debug/` (readable with `run-as`): the luma plane
 * packed row by row (`luma.bin`), the profile's 1440 elevations as little-endian floats
 * (`profile.bin`), and everything else as `key=value` lines (`meta.txt`). Only the newest
 * [KEEP] attempts are kept. Nothing here leaves the device.
 */
object AlignDiagnostics {

    private const val TAG = "CaelisAlign"
    private const val KEEP = 6

    fun record(
        context: Context,
        frame: LumaFrame,
        geometry: CameraFrameGeometry,
        absolute: Boolean,
        calibrationAzimuthDeg: Float,
        calibrationPitchDeg: Float,
        fov: FovResult,
        profile: HorizonProfile,
        fit: SkylineFit,
    ) {
        Log.d(
            TAG,
            "refusal=${fit.refusal} columns=${fit.usedColumns} best=${fit.bestAzimuthDegrees}" +
                " residual=${fit.bestResidualDegrees} average=${fit.averageResidualDegrees}" +
                " runnerUp=${fit.runnerUpResidualDegrees} relief=${fit.profileReliefDegrees}" +
                " observedRelief=${fit.observedReliefDegrees} support=${fit.supportingShare} match=${fit.match}" +
                " absolute=$absolute calibration=$calibrationAzimuthDeg/$calibrationPitchDeg" +
                " fov=${geometry.fovHorizontalDegrees} frame=${frame.width}x${frame.height}" +
                " fill=${geometry.fillScale}",
        )
        try {
            val root = File(context.filesDir, "debug")
            val dir = File(root, "align-${System.currentTimeMillis()}")
            if (!dir.mkdirs()) return

            val packed = ByteArray(frame.width * frame.height)
            for (row in 0 until frame.height) {
                System.arraycopy(frame.data, row * frame.rowStride, packed, row * frame.width, frame.width)
            }
            File(dir, "luma.bin").writeBytes(packed)

            val elevations = ByteBuffer.allocate(profile.elevationsDegrees.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            profile.elevationsDegrees.forEach { elevations.putFloat(it) }
            File(dir, "profile.bin").writeBytes(elevations.array())

            val m = geometry.rotationMatrix
            File(dir, "meta.txt").writeText(
                buildString {
                    appendLine("frameWidth=${frame.width}")
                    appendLine("frameHeight=${frame.height}")
                    appendLine("timestampNs=${frame.timestampNs}")
                    appendLine("viewportWidth=${geometry.viewportWidth}")
                    appendLine("viewportHeight=${geometry.viewportHeight}")
                    appendLine("fillScale=${geometry.fillScale}")
                    appendLine("displayRotation=${geometry.displayRotation}")
                    appendLine("fovHorizontalDegrees=${geometry.fovHorizontalDegrees}")
                    appendLine("fovMeasured=${fov.isDynamicStreamCalculated}")
                    appendLine("fovBaseDegrees=${fov.baseHorizontalDegrees}")
                    appendLine("rotationMatrix=${m.joinToString(",")}")
                    appendLine("poseIsUncalibrated=$absolute")
                    appendLine("arcoreFrame=${frame.pose != null}")
                    appendLine("calibrationAzimuthDeg=$calibrationAzimuthDeg")
                    appendLine("calibrationPitchDeg=$calibrationPitchDeg")
                    appendLine("observerLat=${profile.observer.latitude}")
                    appendLine("observerLon=${profile.observer.longitude}")
                    appendLine("observerGroundM=${profile.observerGroundElevationMeters}")
                    appendLine("eyeHeightM=${profile.eyeHeightMeters}")
                    appendLine("coveredSamples=${profile.coveredSamples}")
                    appendLine("refusal=${fit.refusal}")
                    appendLine("usedColumns=${fit.usedColumns}")
                    appendLine("bestAzimuthDeg=${fit.bestAzimuthDegrees}")
                    appendLine("bestResidualDeg=${fit.bestResidualDegrees}")
                    appendLine("averageResidualDeg=${fit.averageResidualDegrees}")
                    appendLine("runnerUpResidualDeg=${fit.runnerUpResidualDegrees}")
                    appendLine("profileReliefDeg=${fit.profileReliefDegrees}")
                    appendLine("observedReliefDeg=${fit.observedReliefDegrees}")
                    appendLine("supportingShare=${fit.supportingShare}")
                    appendLine("matchAzimuthDeg=${fit.match?.azimuthOffsetDegrees}")
                    appendLine("matchElevationDeg=${fit.match?.elevationOffsetDegrees}")
                },
            )

            root.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.deleteRecursively() }
        } catch (e: Exception) {
            Log.w(TAG, "could not write the alignment dump", e)
        }
    }
}
