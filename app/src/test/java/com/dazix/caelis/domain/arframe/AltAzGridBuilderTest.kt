package com.dazix.caelis.domain.arframe

import com.dazix.caelis.domain.arframe.AltAzGridBuilder.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.sqrt

class AltAzGridBuilderTest {

    private data class Seg(val a: FloatArray, val b: FloatArray, val width: Float, val role: Role)

    private val segments = mutableListOf<Seg>().also { list ->
        AltAzGridBuilder.build { ax, ay, az, bx, by, bz, w, role ->
            list += Seg(floatArrayOf(ax, ay, az), floatArrayOf(bx, by, bz), w, role)
        }
    }

    private fun elevationDeg(v: FloatArray) = Math.toDegrees(asin(v[2].toDouble()))

    @Test
    fun `emits exactly the Canvas grid's segment count`() {
        assertEquals(AltAzGridBuilder.SEGMENT_COUNT, segments.size)
        assertEquals(180, segments.count { it.role == Role.HORIZON })
    }

    @Test
    fun `every endpoint is a unit vector`() {
        for (s in segments) for (v in listOf(s.a, s.b)) {
            assertEquals(1.0, sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()), 1e-5)
        }
    }

    @Test
    fun `the horizon is a closed ring at zero elevation, 1_8 px wide`() {
        val horizon = segments.filter { it.role == Role.HORIZON }
        for (s in horizon) {
            assertEquals(0.0, elevationDeg(s.a), 1e-6)
            assertEquals(1.8f, s.width, 0f)
        }
        // Closed: the last segment ends where the first starts.
        val first = horizon.first().a
        val last = horizon.last().b
        for (i in 0 until 3) assertEquals(first[i], last[i], 1e-6f)
    }

    @Test
    fun `meridians stay within plus-minus 85 degrees and never reach the poles`() {
        val meridians = segments.take(12 * 34)
        val elevations = meridians.flatMap { listOf(elevationDeg(it.a), elevationDeg(it.b)) }
        assertEquals(-85.0, elevations.min(), 1e-4)
        assertEquals(85.0, elevations.max(), 1e-4)
    }

    @Test
    fun `cardinal meridians and the 30 and 60 degree rings are major`() {
        val meridians = segments.take(12 * 34)
        assertEquals(4 * 34, meridians.count { it.role == Role.MAJOR })
        assertTrue(meridians.filter { it.role == Role.MAJOR }.all { it.width == 1.2f })
        val rings = segments.drop(12 * 34 + 180)
        val majorRings = rings.filter { it.role == Role.MAJOR }
        assertEquals(2 * 72, majorRings.size)
        assertTrue(majorRings.all { it.width == 1.0f && (kotlin.math.abs(elevationDeg(it.a) - 30.0) < 1e-4 || kotlin.math.abs(elevationDeg(it.a) - 60.0) < 1e-4) })
    }

    @Test
    fun `azimuth zero is north and ninety is east`() {
        val v = FloatArray(3)
        AltAzGridBuilder.direction(0.0, 0.0, v)
        assertEquals(1f, v[1], 1e-6f)
        AltAzGridBuilder.direction(90.0, 0.0, v)
        assertEquals(1f, v[0], 1e-6f)
    }
}
