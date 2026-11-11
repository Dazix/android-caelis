package com.dazix.caelis.domain.horizon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RidgeLinkerTest {

    private val n = HorizonProfile.SAMPLE_COUNT

    /** Points per azimuth, nearest first; the last one of an azimuth is its skyline point. */
    private fun samples(build: MutableMap<Int, MutableList<Pair<Float, Float>>>.() -> Unit): RidgeSamples {
        val map = mutableMapOf<Int, MutableList<Pair<Float, Float>>>().apply(build)
        val offsets = IntArray(n + 1)
        val d = ArrayList<Float>()
        val a = ArrayList<Float>()
        for (i in 0 until n) {
            val points = map[i].orEmpty().sortedBy { it.first }
            points.forEach { d += it.first; a += it.second }
            offsets[i + 1] = d.size
        }
        return RidgeSamples(offsets, d.toFloatArray(), a.toFloatArray())
    }

    private fun MutableMap<Int, MutableList<Pair<Float, Float>>>.ridge(range: IntRange, distance: Float, altitude: Float) {
        for (i in range) getOrPut(i) { mutableListOf() } += distance to altitude
    }

    @Test
    fun `one ridge all the way round is one closed line`() {
        val lines = RidgeLinker.link(samples { ridge(0 until n, 5_000f, 1f) })

        assertEquals(1, lines.size)
        assertTrue(lines[0].closed)
        assertEquals(n, lines[0].size)
    }

    @Test
    fun `a ridge crossing north is one line`() {
        val lines = RidgeLinker.link(samples {
            ridge(1_430 until n, 5_000f, 1f)
            ridge(0..10, 5_000f, 1f)
        })

        assertEquals(1, lines.size)
        assertFalse(lines[0].closed)
        assertEquals(1_430, lines[0].startIndex)
        assertEquals(21, lines[0].size)
    }

    @Test
    fun `a skyline that passes from a near ridge to a far one at about its height is joined`() {
        // The near ridge ends where the far one takes over: nothing of it runs on in front of the
        // far one. Left as two lines, the quarter degree between them read as a hole in the horizon
        // on the device; the near line reaches over to the far one's first point instead.
        val lines = RidgeLinker.link(samples {
            ridge(0..99, 3_000f, 2f)
            ridge(100..199, 25_000f, 1.5f)
        }).sortedBy { it.startIndex }

        assertEquals(2, lines.size)
        assertEquals(0, lines[0].startIndex)
        assertEquals(101, lines[0].size)
        assertEquals(1.5f, lines[0].altitudesDegrees[100])
        assertEquals(100, lines[1].startIndex)
        assertEquals(100, lines[1].size)
    }

    @Test
    fun `lines that meet end to end far apart in height are not joined`() {
        // A step of more than the linking tolerance is a ridge behind another, and reads as one.
        val lines = RidgeLinker.link(samples {
            ridge(0..99, 3_000f, 3f)
            ridge(100..199, 25_000f, 1.5f)
        }).sortedBy { it.startIndex }

        assertEquals(listOf(100, 100), lines.map { it.size })
    }

    @Test
    fun `an inner line is joined to one that carries on right after it`() {
        // The lower line on the device: a near bank broken by a 17 % jump in distance.
        val lines = RidgeLinker.link(samples {
            ridge(0..199, 20_000f, 3f)
            ridge(0..99, 1_260f, -0.16f)
            ridge(100..199, 1_080f, -0.15f)
        }).filter { !it.skyline[0] }.sortedBy { it.startIndex }

        assertEquals(2, lines.size)
        assertEquals(101, lines[0].size)
    }

    @Test
    fun `a far ridge ends a gap before the near ridge that runs on in front of it`() {
        // The near ridge rises in front of the far one's end at 80 and carries on as the skyline:
        // the two overlap there, and the far one stops short of the near one.
        val lines = RidgeLinker.link(samples {
            ridge(0..99, 20_000f, 2f)
            ridge(80..199, 2_000f, 1.8f)
        }).sortedBy { it.startIndex }

        assertEquals(2, lines.size)
        assertEquals(0, lines[0].startIndex)
        assertEquals(100 - RidgeLinker.GAP_SAMPLES, lines[0].size)
        assertEquals(80, lines[1].startIndex)
        assertEquals(120, lines[1].size)
    }

    @Test
    fun `a ridge too short to draw makes no other ridge give way`() {
        // Under Cerna studnice on the device: a ridge 3 km away, 19 samples long, cut the end off the
        // 4 km ridge behind it and was then left out itself, which opened a hole in the skyline.
        val lines = RidgeLinker.link(samples {
            ridge(0..199, 20_000f, 3f)
            ridge(0..99, 8_000f, 2.5f)
            ridge(90..105, 2_000f, 2.3f)
        })

        assertEquals(2, lines.size)
        assertEquals(100, lines.single { !it.skyline[0] }.size)
    }

    @Test
    fun `a far ridge gives way only as far as the near one reaches past it`() {
        // The near ridge runs on one sample in front of the far one's start, then ends: cutting the
        // far one back by the full gap left a sample of sky with no line at all.
        val lines = RidgeLinker.link(samples {
            ridge(0..100, 4_000f, 2f)
            ridge(100..199, 12_000f, 2.05f)
        }).sortedBy { it.startIndex }

        // ...and the near one, ending right before the far one's new start, reaches over to it.
        assertEquals(2, lines.size)
        assertEquals(0, lines[0].startIndex)
        assertEquals(102, lines[0].size)
        assertEquals(101, lines[1].startIndex)
        assertEquals(99, lines[1].size)
    }

    @Test
    fun `a short stretch of skyline between two ridges is still drawn`() {
        // 1.5 degrees of a middle ridge on the skyline: too short for an inner line, but leaving it
        // out cut a hole in the horizon on the device.
        val lines = RidgeLinker.link(samples {
            ridge(0..99, 3_000f, 2f)
            ridge(100..105, 12_000f, 2f)
            ridge(106..199, 30_000f, 2f)
        })

        val drawn = BooleanArray(n)
        for (line in lines) for (k in 0 until line.size) if (line.skyline[k]) drawn[(line.startIndex + k) % n] = true
        assertEquals((0..199).filter { !drawn[it] }.toString(), true, (0..199).all { drawn[it] })
    }

    @Test
    fun `a ridge that fades out keeps its full length`() {
        val lines = RidgeLinker.link(samples { ridge(0..49, 5_000f, 1f) })

        assertEquals(1, lines.size)
        assertEquals(50, lines[0].size)
    }

    @Test
    fun `a jump in distance breaks an inner ridge`() {
        // 20 % further: below the skyline that is another ridge.
        val lines = RidgeLinker.link(samples {
            ridge(0..49, 5_000f, 1f)
            ridge(50..99, 6_000f, 1f)
            ridge(0..99, 20_000f, 3f)
        })

        assertEquals(3, lines.size)
    }

    @Test
    fun `a large jump in distance breaks the skyline`() {
        // 50 % further: the skyline has passed to a ridge behind.
        val lines = RidgeLinker.link(samples {
            ridge(0..49, 5_000f, 1f)
            ridge(50..99, 7_500f, 1f)
        })

        assertEquals(2, lines.size)
    }

    @Test
    fun `inner lines shorter than the minimum are dropped`() {
        val short = RidgeLinker.MIN_LINE_SAMPLES - 1
        val skyline: MutableMap<Int, MutableList<Pair<Float, Float>>>.() -> Unit = { ridge(0..199, 20_000f, 3f) }
        assertEquals(1, RidgeLinker.link(samples { skyline(); ridge(0 until short, 5_000f, 1f) }).size)
        assertEquals(2, RidgeLinker.link(samples { skyline(); ridge(0..short, 5_000f, 1f) }).size)
    }

    @Test
    fun `a ridge only three degrees wide is not drawn`() {
        // A knoll; 2 degrees as the minimum hatched the view with short strokes.
        val lines = RidgeLinker.link(samples {
            ridge(0..199, 20_000f, 3f)
            ridge(0 until 12, 5_000f, 1f)
        })
        assertEquals(1, lines.size)
        assertTrue(lines[0].skyline.all { it })
    }

    @Test
    fun `points carry their altitude and whether they are the skyline`() {
        val lines = RidgeLinker.link(samples {
            ridge(0..19, 2_000f, 1f)
            ridge(0..19, 9_000f, 3f)
        }).sortedBy { it.altitudesDegrees[0] }

        assertEquals(2, lines.size)
        assertTrue(lines[0].skyline.none { it })
        assertEquals(1f, lines[0].altitudesDegrees[5])
        assertTrue(lines[1].skyline.all { it })
        assertEquals(3f, lines[1].altitudesDegrees[5])
    }

    @Test
    fun `a ridge that spirals past a full turn is never longer than one turn`() {
        // Distance creeping up all the way round, then a second turn carrying on from 359.75 degrees:
        // one open chain of 1540 points, which the scene's path buffer cannot hold.
        val lines = RidgeLinker.link(samples {
            for (i in 0 until n) getOrPut(i) { mutableListOf() } += (5_000f + i * 2.5f) to 1f
            for (i in 0..99) getOrPut(i) { mutableListOf() } += (5_000f + (n + i) * 2.5f) to 1f
        })

        assertTrue(lines.isNotEmpty())
        assertTrue(lines.joinToString { "${it.size}" }, lines.all { it.size <= n })
    }

    @Test
    fun `a skyline whose crest wanders in distance on a broad summit stays one line`() {
        // A rounded plateau edge: the highest sample jumps between 4 and 5.2 km from ray to ray.
        val lines = RidgeLinker.link(samples {
            for (i in 0..199) getOrPut(i) { mutableListOf() } += (if (i % 3 == 0) 5_200f else 4_000f) to (1f + i * 0.001f)
        })

        assertEquals(1, lines.size)
        assertEquals(200, lines[0].size)
    }

    @Test
    fun `a skyline a few hundred metres away is not cut up by the sampling step`() {
        val lines = RidgeLinker.link(samples {
            for (i in 0..99) getOrPut(i) { mutableListOf() } += (if (i % 2 == 0) 210f else 270f) to 4f
        })

        assertEquals(1, lines.size)
        assertEquals(100, lines[0].size)
    }

    @Test
    fun `flat sea links to nothing`() {
        assertTrue(RidgeLinker.link(samples { }).isEmpty())
    }

    @Test
    fun `a rugged profile at the cap never yields more points than it has`() {
        val random = Random(7)
        val cap = RidgeSamples.MAX_INNER_RIDGES_PER_AZIMUTH + 1
        val s = samples {
            for (i in 0 until n) {
                getOrPut(i) { mutableListOf() } += List(cap) { k ->
                    (1_000f + k * 3_000f + random.nextFloat() * 400f) to (k + random.nextFloat())
                }
            }
        }

        val lines = RidgeLinker.link(s)

        assertTrue(lines.sumOf { it.size } <= s.offsets.last())
        // The minimum holds for inner lines; a stretch of skyline is drawn at any length.
        assertTrue(lines.all { it.closed || it.size >= RidgeLinker.MIN_LINE_SAMPLES || it.skyline.any { s -> s } })
        assertTrue(lines.all { it.size <= n })
    }
}
