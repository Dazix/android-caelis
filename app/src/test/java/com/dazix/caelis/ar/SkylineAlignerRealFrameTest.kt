package com.dazix.caelis.ar

import android.view.Surface
import com.dazix.caelis.data.horizon.HorizonProfileCodec
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.solar.GeoLocation
import com.dazix.caelis.domain.horizon.SkylineExtractor
import com.dazix.caelis.domain.horizon.SkylineMatch
import com.dazix.caelis.domain.horizon.SkylineMatcher
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

        val fit = SkylineAligner.fit(frame, believedGeometry(frame), profile())
        val match = fit.match

        assertNotNull("a clear ridge over an overcast sky must be matched: $fit", match)
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

        val match = SkylineAligner.align(frame, believedGeometry(frame), profile())

        assertEquals(null, match)
    }

    /** The geometry the sensor path builds: CameraX luma, FILL_CENTER, the pose the overlay was drawn with. */
    private fun believedGeometry(frame: LumaFrame) = CameraFrameGeometry.fillCenter(
        frameWidth = frame.width,
        frameHeight = frame.height,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        rotationMatrix = rotationMatrix(believedYaw, believedPitch, believedRoll),
        displayRotation = Surface.ROTATION_0,
        fovHorizontalDegrees = believedFov,
    )

    /**
     * The alignment as it was written before it took a [CameraFrameGeometry], with the loose
     * parameters inlined, kept here as the reference. The point of the geometry type is that a
     * second frame source can feed the aligner; it must not change what the first one gets.
     */
    private fun alignWithLooseParameters(
        frame: LumaFrame,
        viewportWidth: Float,
        viewportHeight: Float,
        rotationMatrix: FloatArray,
        displayRotation: Int,
        fovHorizontalDegrees: Float,
    ): SkylineMatch? {
        val stride = SkylineAligner.COLUMN_STRIDE
        val columns = (frame.width + stride - 1) / stride
        val k = SkylineExtractor.MAX_CANDIDATES
        val rows = IntArray(columns * k)
        val counts = IntArray(columns)
        val sampled = SkylineExtractor.extractCandidates(
            luma = frame.data,
            width = frame.width,
            height = frame.height,
            rowStride = frame.rowStride,
            columnStride = stride,
            outRows = rows,
            outCounts = counts,
        )
        if (sampled <= 0) return null
        val scale = maxOf(viewportWidth / frame.width, viewportHeight / frame.height)
        val azimuths = DoubleArray(sampled * k)
        val elevations = DoubleArray(sampled * k)
        val columnStart = IntArray(sampled)
        val columnCount = IntArray(sampled)
        val azEl = DoubleArray(2)
        var used = 0
        for (i in 0 until sampled) {
            columnStart[i] = used
            val screenX = (i * stride - frame.width * 0.5f) * scale + viewportWidth * 0.5f
            if (screenX < 0f || screenX > viewportWidth) continue
            for (j in 0 until counts[i]) {
                val screenY = (rows[i * k + j] - frame.height * 0.5f) * scale + viewportHeight * 0.5f
                if (screenY < 0f || screenY > viewportHeight) continue
                CameraProjection.unprojectFast(
                    screenX = screenX,
                    screenY = screenY,
                    rotationMatrix = rotationMatrix,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                    displayRotation = displayRotation,
                    fovHorizontalDegrees = fovHorizontalDegrees,
                    outAzEl = azEl,
                )
                azimuths[used] = azEl[0]
                elevations[used] = azEl[1]
                used++
            }
            columnCount[i] = used - columnStart[i]
        }
        return SkylineMatcher.fitCandidates(azimuths, elevations, columnStart, columnCount, sampled, profile()).match
    }

    @Test
    fun `a geometry built from the old loose inputs gives the identical alignment`() {
        val frame = frameFromMeasuredRidge()
        val matrix = rotationMatrix(believedYaw, believedPitch, believedRoll)

        val before = alignWithLooseParameters(
            frame, viewportWidth, viewportHeight, matrix, Surface.ROTATION_0, believedFov,
        )
        val after = SkylineAligner.align(frame, believedGeometry(frame), profile())

        assertNotNull("the reference must match the real frame, or this proves nothing", before)
        // A data class: every offset, the residual, the column count and the determined flag, exactly.
        assertEquals(before, after)
    }

    @Test
    fun `the fill scale is the one FILL_CENTER applies`() {
        // Wider than the viewport's aspect: the height decides and the sides are cropped.
        assertEquals(2f, CameraFrameGeometry.fillCenterScale(540, 1170, 1080f, 2340f), 0f)
        assertEquals(2f, CameraFrameGeometry.fillCenterScale(1000, 1170, 1080f, 2340f), 0f)
        // Taller than the viewport's aspect: the width decides and the top and bottom are cropped.
        assertEquals(2f, CameraFrameGeometry.fillCenterScale(540, 600, 1080f, 1000f), 0f)
        assertEquals(0f, CameraFrameGeometry.fillCenterScale(0, 600, 1080f, 1000f), 0f)
    }

    @Test
    fun `an explicit fill scale is used instead of the derived one`() {
        val frame = frameFromMeasuredRidge()
        val derived = believedGeometry(frame)
        val explicit = CameraFrameGeometry(
            frameWidth = derived.frameWidth,
            frameHeight = derived.frameHeight,
            viewportWidth = derived.viewportWidth,
            viewportHeight = derived.viewportHeight,
            fillScale = derived.fillScale,
            rotationMatrix = derived.rotationMatrix,
            displayRotation = derived.displayRotation,
            fovHorizontalDegrees = derived.fovHorizontalDegrees,
        )
        val sameScale = SkylineAligner.align(frame, explicit, profile())
        assertEquals(SkylineAligner.align(frame, derived, profile()), sameScale)

        // A source that fits its picture more loosely maps every column somewhere else, so the answer
        // has to move; if it did not, the aligner would be ignoring the scale it was given.
        val looser = CameraFrameGeometry(
            frameWidth = derived.frameWidth,
            frameHeight = derived.frameHeight,
            viewportWidth = derived.viewportWidth,
            viewportHeight = derived.viewportHeight,
            fillScale = derived.fillScale * 1.1f,
            rotationMatrix = derived.rotationMatrix,
            displayRotation = derived.displayRotation,
            fovHorizontalDegrees = derived.fovHorizontalDegrees,
        )
        val moved = SkylineAligner.align(frame, looser, profile())
        assertTrue("a different fill scale must change the result", moved != sameScale)
    }

    @Test
    fun `a geometry that does not describe the frame is refused`() {
        val frame = frameFromMeasuredRidge()
        val good = believedGeometry(frame)

        fun geometry(
            frameWidth: Int = good.frameWidth,
            frameHeight: Int = good.frameHeight,
            viewportWidth: Float = good.viewportWidth,
            fovHorizontalDegrees: Float = good.fovHorizontalDegrees,
            fillScale: Float = good.fillScale,
        ) = CameraFrameGeometry(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            viewportWidth = viewportWidth,
            viewportHeight = good.viewportHeight,
            fillScale = fillScale,
            rotationMatrix = good.rotationMatrix,
            displayRotation = good.displayRotation,
            fovHorizontalDegrees = fovHorizontalDegrees,
        )

        assertNotNull(SkylineAligner.align(frame, geometry(), profile()))
        assertEquals("frame of another size", null, SkylineAligner.align(frame, geometry(frameWidth = 270), profile()))
        assertEquals("frame of another height", null, SkylineAligner.align(frame, geometry(frameHeight = 585), profile()))
        assertEquals("unmeasured viewport", null, SkylineAligner.align(frame, geometry(viewportWidth = 0f), profile()))
        assertEquals("no field of view yet", null, SkylineAligner.align(frame, geometry(fovHorizontalDegrees = 0f), profile()))
        assertEquals("NaN field of view", null, SkylineAligner.align(frame, geometry(fovHorizontalDegrees = Float.NaN), profile()))
        assertEquals("no fill scale", null, SkylineAligner.align(frame, geometry(fillScale = 0f), profile()))
    }

    /**
     * A rooftop in Liberec on 16 October 2026, backlit, the sun in the frame and cumulus over Ještěd:
     * every one of six automatic attempts was refused and the horizon had to be aligned by hand.
     *
     * The ridge was a clear step (37–50/255) in every column, but the clouds above it had edges as
     * strong, and the parapet below it twice as strong. Taking the first step from the top put the
     * boundary on a cloud in most columns and on a roof in the rest. The fixture is that frame's
     * measured downward steps, column by column (`rooftop-steps.txt`), rebuilt into a luma plane the
     * same way the Vratislavice ridge is, so no photograph of the place is committed. The profile is
     * the one the phone used, its 1440 elevations only; where it was computed is not part of it.
     */
    private fun rooftopFrame(): LumaFrame {
        val width = 480
        val height = 640
        val data = ByteArray(width * height)
        val column = IntArray(height)
        val lines = String(resource("rooftop-steps.txt")).lines().filter { it.isNotBlank() && !it.startsWith("#") }
        check(lines.size == width / SkylineAligner.COLUMN_STRIDE) { "fixture has ${lines.size} columns" }
        for (line in lines) {
            val fields = line.trim().split(' ')
            val x = fields[0].toInt()
            // Each step drops the brightness from its row down; a drop that would go below black is
            // preceded by a jump back up halfway from the previous step, an upward edge, which is
            // not a candidate.
            column.fill(ROOFTOP_TOP_LUMA)
            var value = ROOFTOP_TOP_LUMA
            var previous = 0
            for (step in fields.drop(1)) {
                val (row, contrast) = step.split(':').map { it.toInt() }
                if (value - contrast < 0) {
                    column.fill(ROOFTOP_TOP_LUMA, (previous + row) / 2, height)
                    value = ROOFTOP_TOP_LUMA
                }
                value -= contrast
                column.fill(value, row, height)
                previous = row
            }
            for (xx in x until minOf(width, x + SkylineAligner.COLUMN_STRIDE)) {
                for (y in 0 until height) data[y * width + xx] = column[y].toByte()
            }
        }
        return LumaFrame(data, width, height, width)
    }

    private fun rooftopProfile(): HorizonProfile {
        val buffer = ByteBuffer.wrap(resource("rooftop-elevations.bin")).order(ByteOrder.LITTLE_ENDIAN)
        val elevations = FloatArray(HorizonProfile.SAMPLE_COUNT) { buffer.getFloat() }
        return HorizonProfile(
            elevationsDegrees = elevations,
            observer = GeoLocation(0.0, 0.0, 0.0),
            observerGroundElevationMeters = 432.0,
            eyeHeightMeters = 1.6,
            maxRangeMeters = 20_000.0,
            generatedAtEpochMillis = 0L,
        )
    }

    /** The pose of that attempt as the aligner got it: the reference matrix, no calibration in it. */
    private fun rooftopGeometry(frame: LumaFrame) = CameraFrameGeometry(
        frameWidth = frame.width,
        frameHeight = frame.height,
        viewportWidth = 1080f,
        viewportHeight = 2340f,
        fillScale = 3.65625f,
        rotationMatrix = floatArrayOf(
            -0.12176826f, -0.03785989f, 0.99183637f, 0f,
            0.99248976f, -0.016423f, 0.12122149f, 0f,
            0.011699349f, 0.99914825f, 0.03957522f, 0f,
            0f, 0f, 0f, 1f,
        ),
        displayRotation = Surface.ROTATION_0,
        fovHorizontalDegrees = 36.713722f,
    )

    @Test
    fun `a ridge under clouds and over roofs is found by the terrain, not by the first edge`() {
        val frame = rooftopFrame()

        val fit = SkylineAligner.fit(frame, rooftopGeometry(frame), rooftopProfile())

        assertEquals("the backlit rooftop frame must be accepted", null, fit.refusal)
        val match = checkNotNull(fit.match)
        assertTrue(match.azimuthDetermined)
        // The fit itself, replayed offline from the dump, put the compass 16.0° and the pitch 0.5°
        // out; by hand, right after these attempts, the user dragged the ridge on at -16.4° / -0.47°.
        // The heading was not locked in between, so the hand value is a check to a degree, the
        // offline fit to a quarter.
        assertEquals("compass", -16.0, match.azimuthOffsetDegrees, 0.25)
        assertEquals("pitch", -0.5, match.elevationOffsetDegrees, 0.25)
        assertEquals("compass against the hand alignment", -16.4, match.azimuthOffsetDegrees, 1.0)
        assertEquals("pitch against the hand alignment", -0.47, match.elevationOffsetDegrees, 1.0)
        assertTrue("residual ${match.residualDegrees}", match.residualDegrees < 0.2)
    }

    private companion object {
        const val SKY_LUMA: Byte = 205.toByte()
        const val GROUND_LUMA: Byte = 95.toByte()
        const val ROOFTOP_TOP_LUMA = 250
    }
}
