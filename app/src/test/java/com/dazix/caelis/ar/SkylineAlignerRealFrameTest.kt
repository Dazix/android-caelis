package com.dazix.caelis.ar

import android.view.Surface
import com.dazix.caelis.data.horizon.HorizonProfileCodec
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic alignment, run against the photograph that started all of this.
 *
 * The geometry is real: the ridge line was measured from the Sun AR screenshot taken at
 * Liberec-Vratislavice on 12 September 2026 looking west at Ještěd, and the profile is the one the
 * phone had computed for that spot. The answer is known independently — fitting the profile to the
 * photographed ridge offline put the compass 2.9° low and the pitch 1.3° high, with the drawn overlay
 * reproducing the app's own orientation to 0.25 px.
 *
 * The luma is rebuilt from that measured ridge rather than being the screenshot itself, because the
 * screenshot has the app's own overlay burned into it — black label pills sit right across the
 * skyline and dominate any brightness step. A camera frame on the device carries no such graphics, so
 * feeding the screenshot verbatim would be testing the extractor against a picture it will never see.
 */
class SkylineAlignerRealFrameTest {

    // The screenshot, greyscaled and halved: 540x1170 covering the full 1080x2340 screen.
    private val frameWidth = 540
    private val frameHeight = 1170
    private val viewportWidth = 1080f
    private val viewportHeight = 2340f

    /** What the app believed while it drew that frame, recovered from the overlay it drew. */
    private val believedYaw = 250.68
    private val believedPitch = 2.04
    private val believedRoll = 3.48
    private val believedFov = 36.7f

    /** What the terrain says is true, from the offline fit of the profile to the photographed ridge. */
    private val trueYaw = 253.55
    private val truePitch = 0.78

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("skyline/$name")) {
            "missing test fixture skyline/$name"
        }.use { it.readBytes() }

    private fun profile() = checkNotNull(
        HorizonProfileCodec.read(resource("vratislavice-profile.bin").inputStream()),
    ) { "the stored profile must decode" }

    /**
     * The rotation matrix `projectFast` expects: its rows, read down the columns of the 4x4, are the
     * device's right, up and backward axes in world coordinates.
     */
    private fun rotationMatrix(yawDeg: Double, pitchDeg: Double, rollDeg: Double): FloatArray {
        val yaw = Math.toRadians(yawDeg)
        val pitch = Math.toRadians(pitchDeg)
        val roll = Math.toRadians(rollDeg)

        val forward = doubleArrayOf(sin(yaw) * cos(pitch), cos(yaw) * cos(pitch), sin(pitch))
        val right0 = doubleArrayOf(cos(yaw), -sin(yaw), 0.0)
        val up0 = doubleArrayOf(
            right0[1] * forward[2] - right0[2] * forward[1],
            right0[2] * forward[0] - right0[0] * forward[2],
            right0[0] * forward[1] - right0[1] * forward[0],
        )
        val right = DoubleArray(3) { right0[it] * cos(roll) + up0[it] * sin(roll) }
        val up = DoubleArray(3) { -right0[it] * sin(roll) + up0[it] * cos(roll) }

        val m = FloatArray(16)
        m[0] = right[0].toFloat(); m[4] = right[1].toFloat(); m[8] = right[2].toFloat()
        m[1] = up[0].toFloat(); m[5] = up[1].toFloat(); m[9] = up[2].toFloat()
        m[2] = (-forward[0]).toFloat(); m[6] = (-forward[1]).toFloat(); m[10] = (-forward[2]).toFloat()
        m[15] = 1f
        return m
    }

    /**
     * The measured ridge, as a frame: bright sky above the line, dark ground below it. Columns the
     * overlay made unreadable in the original are left as sky all the way down, which is what a
     * column with no boundary looks like and which the extractor is expected to discard.
     */
    private fun frameFromMeasuredRidge(): LumaFrame {
        val fixture = resource("vratislavice-ridge.bin")
        val columns = ((fixture[0].toInt() and 0xFF) shl 8) or (fixture[1].toInt() and 0xFF)
        check(columns == frameWidth) { "fixture has $columns columns, expected $frameWidth" }

        val data = ByteArray(frameWidth * frameHeight) { SKY_LUMA }
        for (x in 0 until columns) {
            val offset = 2 + x * 2
            val row = (((fixture[offset].toInt() and 0xFF) shl 8) or (fixture[offset + 1].toInt() and 0xFF))
                .toShort()
                .toInt()
            if (row < 0) continue
            for (y in row until frameHeight) {
                data[y * frameWidth + x] = GROUND_LUMA
            }
        }
        return LumaFrame(data, frameWidth, frameHeight, frameWidth)
    }

    @Test
    fun `it finds the real compass error in the photograph it was diagnosed from`() {
        val frame = frameFromMeasuredRidge()

        val match = SkylineAligner.align(
            frame = frame,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            rotationMatrix = rotationMatrix(believedYaw, believedPitch, believedRoll),
            displayRotation = Surface.ROTATION_0,
            fovHorizontalDegrees = believedFov,
            profile = profile(),
        )

        assertNotNull("a clear ridge over an overcast sky must be matched", match)
        match!!
        assertTrue("a ridge like Ještěd pins the compass", match.azimuthDetermined)

        // The tight assertion is the residual: after the offsets it returns, the terrain profile and
        // the photographed ridge have to lie on top of each other. That is the thing the feature
        // claims and the thing a regression would break.
        assertTrue(
            "the two skylines must coincide after the correction, residual was ${match.residualDegrees}",
            match.residualDegrees < 0.2,
        )

        // The offsets themselves are checked more loosely on purpose. The offline diagnosis used a
        // trimmed least-squares fit over yaw, pitch, roll and FOV together; this matcher shifts in
        // two axes with a robust quantile loss. Neither is ground truth, and the offline estimate
        // alone moved 0.3° across plausible FOV assumptions — so agreement to a degree is the honest
        // claim, and a wild answer is what this guards against.
        assertEquals(
            "the compass was measured about 2.9° low",
            trueYaw - believedYaw,
            match.azimuthOffsetDegrees,
            1.0,
        )
        assertEquals(
            "the pitch was measured about 1.3° high",
            truePitch - believedPitch,
            match.elevationOffsetDegrees,
            1.0,
        )
    }

    /**
     * A frame with no sky/terrain boundary must come back empty-handed. Anything else means the
     * matcher is fitting noise, and a confident wrong alignment is worse than none at all.
     */
    @Test
    fun `a uniform frame is refused`() {
        val frame = LumaFrame(
            data = ByteArray(frameWidth * frameHeight) { 120.toByte() },
            width = frameWidth,
            height = frameHeight,
            rowStride = frameWidth,
        )

        val match = SkylineAligner.align(
            frame = frame,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            rotationMatrix = rotationMatrix(believedYaw, believedPitch, believedRoll),
            displayRotation = Surface.ROTATION_0,
            fovHorizontalDegrees = believedFov,
            profile = profile(),
        )

        assertEquals(null, match)
    }

    private companion object {
        const val SKY_LUMA: Byte = 205.toByte()
        const val GROUND_LUMA: Byte = 95.toByte()
    }
}
