package com.dazix.caelis.domain.stability

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The detector is judged on synthetic gyroscope traces at 50 Hz (SENSOR_DELAY_GAME): a hand's tremor,
 * a tripod's noise floor, and a tripod hit by the shutter tap — the case a peak or RMS detector gets
 * wrong and the reason this one counts the fraction of samples above its threshold.
 */
class HandheldDetectorTest {

    private val stepMs = 20L

    /** 1 °/s of 9 Hz tremor on two axes: a hand trying to hold still, on the quiet side. */
    private fun tremor(tMs: Long): FloatArray {
        val t = tMs / 1000.0
        val amplitude = Math.toRadians(1.0)
        // Two axes out of phase, so the magnitude never sits at a zero crossing for long.
        return floatArrayOf(
            (amplitude * sin(2 * PI * 9 * t)).toFloat(),
            (amplitude * sin(2 * PI * 9 * t + PI / 2)).toFloat(),
            (0.2 * amplitude * sin(2 * PI * 3 * t)).toFloat(),
        )
    }

    /** ±0.05 °/s: a tripod, well under the 0.3 °/s threshold. */
    private fun tripodNoise(tMs: Long): FloatArray {
        val n = Math.toRadians(0.05) * sin(tMs * 0.7)
        return floatArrayOf(n.toFloat(), (-n).toFloat(), (n / 2).toFloat())
    }

    private fun HandheldDetector.feed(
        fromMs: Long,
        toMs: Long,
        sample: (Long) -> FloatArray,
    ): HandheldState {
        var t = fromMs
        var last = state
        while (t <= toMs) {
            val v = sample(t)
            last = add(t, v[0], v[1], v[2])
            t += stepMs
        }
        return last
    }

    @Test
    fun `a hand is recognised once the window is full and not before`() {
        val detector = HandheldDetector()

        assertEquals(HandheldState.UNKNOWN, detector.feed(0L, 2_600L, ::tremor))
        assertEquals(HandheldState.HANDHELD, detector.feed(2_620L, 3_000L, ::tremor))
    }

    @Test
    fun `a tripod reads as steady`() {
        val detector = HandheldDetector()

        assertEquals(HandheldState.STEADY, detector.feed(0L, 3_000L, ::tripodNoise))
    }

    @Test
    fun `the shutter tap on a tripod does not raise the warning`() {
        val detector = HandheldDetector()
        detector.feed(0L, 3_000L, ::tripodNoise)

        // 300 ms of a hard knock, 20 °/s, then the tripod settles again.
        val knock = Math.toRadians(20.0).toFloat()
        var t = 3_020L
        while (t <= 3_320L) {
            assertEquals(HandheldState.STEADY, detector.add(t, knock, -knock, knock))
            t += stepMs
        }
        assertEquals(HandheldState.STEADY, detector.feed(3_340L, 9_000L, ::tripodNoise))
    }

    @Test
    fun `the warning clears three seconds after the phone is put down`() {
        val detector = HandheldDetector()
        detector.feed(0L, 3_000L, ::tremor)

        assertEquals(HandheldState.HANDHELD, detector.feed(3_020L, 4_000L, ::tripodNoise))
        assertEquals(HandheldState.STEADY, detector.feed(4_020L, 6_100L, ::tripodNoise))
    }

    @Test
    fun `between the two fractions the previous answer stands`() {
        // Every other sample of a tremor: 50 % above the threshold, inside the hysteresis band.
        val half: (Long) -> FloatArray = { t -> if ((t / stepMs) % 2 == 0L) tremorPeak() else tripodNoise(t) }

        val fromSteady = HandheldDetector()
        fromSteady.feed(0L, 3_000L, ::tripodNoise)
        assertEquals(HandheldState.STEADY, fromSteady.feed(3_020L, 9_000L, half))

        val fromHand = HandheldDetector()
        fromHand.feed(0L, 3_000L, ::tremor)
        assertEquals(HandheldState.HANDHELD, fromHand.feed(3_020L, 9_000L, half))

        // A borderline first window is not an answer at all.
        assertEquals(HandheldState.UNKNOWN, HandheldDetector().feed(0L, 9_000L, half))
    }

    private fun tremorPeak(): FloatArray {
        val r = Math.toRadians(1.0).toFloat()
        return floatArrayOf(r, 0f, 0f)
    }

    @Test
    fun `a gap in the samples starts the window over`() {
        val detector = HandheldDetector()
        detector.feed(0L, 3_000L, ::tremor)

        // The sensor went quiet for 600 ms (a stopped listener, a CPU asleep).
        val v = tremor(3_600L)
        assertEquals(HandheldState.UNKNOWN, detector.add(3_600L, v[0], v[1], v[2]))
        assertEquals(HandheldState.UNKNOWN, detector.feed(3_620L, 6_200L, ::tremor))
        assertEquals(HandheldState.HANDHELD, detector.feed(6_220L, 6_400L, ::tremor))
    }

    @Test
    fun `a clock running backwards resets instead of corrupting the window`() {
        val detector = HandheldDetector()
        detector.feed(0L, 3_000L, ::tremor)

        val v = tremor(1_000L)
        assertEquals(HandheldState.UNKNOWN, detector.add(1_000L, v[0], v[1], v[2]))
    }

    @Test
    fun `a sensor faster than the ring holds stays correct`() {
        // 5 ms steps for 3 s is 600 samples, exactly the capacity; push past it.
        val detector = HandheldDetector(capacity = 100)
        var t = 0L
        var last = HandheldState.UNKNOWN
        while (t <= 4_000L) {
            val v = tremor(t)
            last = detector.add(t, v[0], v[1], v[2])
            t += 5L
        }
        // With only 0.5 s of samples kept the window never covers 2.7 s: no answer, never a wrong one.
        assertEquals(HandheldState.UNKNOWN, last)
    }

    @Test
    fun `reset forgets everything`() {
        val detector = HandheldDetector()
        detector.feed(0L, 3_000L, ::tremor)
        detector.reset()

        assertEquals(HandheldState.UNKNOWN, detector.state)
        assertEquals(HandheldState.UNKNOWN, detector.feed(10_000L, 12_000L, ::tremor))
    }
}
