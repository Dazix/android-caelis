package com.dazix.caelis.ar.pose

import com.dazix.caelis.domain.arframe.ArWorldAlignment
import kotlin.math.cos
import kotlin.math.sin

/**
 * A synthetic world for the pose tests: ARCore's world is [worldYawDegrees] away from ENU, and the
 * "sensor tracker" reports exactly what an ideal compass would. Nothing here calls the code under
 * test: matrices are built from rotation formulas, and the expected pose is derived independently.
 */
internal object PoseWorld {

    fun rx(a: Double) = floatArrayOf(1f, 0f, 0f, 0f, cos(a).toFloat(), -sin(a).toFloat(), 0f, sin(a).toFloat(), cos(a).toFloat())
    fun ry(a: Double) = floatArrayOf(cos(a).toFloat(), 0f, sin(a).toFloat(), 0f, 1f, 0f, -sin(a).toFloat(), 0f, cos(a).toFloat())
    fun rz(a: Double) = floatArrayOf(cos(a).toFloat(), -sin(a).toFloat(), 0f, sin(a).toFloat(), cos(a).toFloat(), 0f, 0f, 0f, 1f)

    fun mul(a: FloatArray, b: FloatArray) = FloatArray(9).also { o ->
        for (r in 0 until 3) for (c in 0 until 3) o[r * 3 + c] = (0 until 3).sumOf { k -> (a[r * 3 + k] * b[k * 3 + c]).toDouble() }.toFloat()
    }

    /** The phone turning slowly: a different device pose for every frame index. */
    fun device(i: Int): FloatArray = mul(ry(0.4 + 0.01 * i), mul(rx(-0.3 + 0.004 * i), rz(0.1)))

    /** device -> ENU for a device at [arDevice] when ARCore's world is turned [worldYawDegrees]. */
    fun truth(worldYawDegrees: Double, arDevice: FloatArray): FloatArray =
        mul(rz(Math.toRadians(worldYawDegrees)), mul(ArWorldAlignment.EUS_TO_ENU, arDevice))

    /** A 3x3 row-major rotation as the 4x4 `SensorManager` layout. */
    fun as4x4(m: FloatArray) = FloatArray(16).also {
        for (r in 0 until 3) for (c in 0 until 3) it[r * 4 + c] = m[r * 3 + c]
        it[15] = 1f
    }
}

/** An ideal compass tracker: it knows the true device pose of every instant. */
internal class FakeSensorPose(
    private val worldYawDegrees: () -> Double,
    private val deviceAt: (Long) -> FloatArray,
) : SensorPose {
    override val kind = PoseKind.SENSOR
    override var headingAccuracy = 3
    override var calibrationAzimuthDegrees = 0f
    override var calibrationPitchDegrees = 0f

    /** Whether the sensor history can bracket the requested instant. */
    var hasHistory = true
    var sensorMatrixRequests = 0

    override fun referenceMatrix(timestampNs: Long, out16: FloatArray): Boolean {
        if (!hasHistory) return false
        PoseWorld.as4x4(PoseWorld.truth(worldYawDegrees(), deviceAt(timestampNs))).copyInto(out16)
        return true
    }

    override fun renderMatrix(imageTimestampNs: Long, out16: FloatArray): Boolean {
        sensorMatrixRequests++
        // Marked so a test can tell it is the sensors' matrix: the identity.
        out16.fill(0f)
        out16[0] = 1f; out16[5] = 1f; out16[10] = 1f; out16[15] = 1f
        return true
    }
}

/** A scripted ARCore: the test pushes the frames it wants to see. */
internal class FakeFrameSource : ArFrameSource {
    override var sessionEpoch = 0
    private val queue = ArrayDeque<ArFrameSample>()
    var displayRotation = -1
    var displayWidth = 0
    var displayHeight = 0
    var lumaCapture: ArLumaCapture? = null
    var polls = 0

    fun push(sample: ArFrameSample) = queue.addLast(sample)

    override fun poll(out: ArFrameSample): Boolean {
        polls++
        val next = queue.removeFirstOrNull() ?: return false
        out.clear()
        out.timestampNs = next.timestampNs
        out.tracking = next.tracking
        out.sessionEpoch = sessionEpoch
        next.devicePose.copyInto(out.devicePose)
        next.cameraPose.copyInto(out.cameraPose)
        out.fovHorizontalDegrees = next.fovHorizontalDegrees
        out.geospatialValid = next.geospatialValid
        next.geospatialEus.copyInto(out.geospatialEus)
        out.geospatialYawAccuracyDegrees = next.geospatialYawAccuracyDegrees
        return true
    }

    override fun setDisplayGeometry(displayRotation: Int, width: Int, height: Int) {
        this.displayRotation = displayRotation
        displayWidth = width
        displayHeight = height
    }

    override fun copyLuma(): ArLumaCapture? = lumaCapture
}

internal fun arSample(
    timestampNs: Long,
    tracking: ArTrackingState = ArTrackingState.TRACKING,
    deviceIndex: Int = 0,
    fov: Float = 52f,
) = ArFrameSample().also {
    it.timestampNs = timestampNs
    it.tracking = tracking
    PoseWorld.device(deviceIndex).copyInto(it.devicePose)
    it.fovHorizontalDegrees = fov
}
