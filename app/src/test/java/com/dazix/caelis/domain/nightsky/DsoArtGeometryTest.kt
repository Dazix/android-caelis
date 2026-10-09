package com.dazix.caelis.domain.nightsky

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.asin
import kotlin.math.atan2

class DsoArtGeometryTest {

    private fun raDec(out: FloatArray, i: Int): Pair<Double, Double> {
        val x = out[i * 3].toDouble(); val y = out[i * 3 + 1].toDouble(); val z = out[i * 3 + 2].toDouble()
        val ra = Math.toDegrees(atan2(z, x)).let { if (it < 0) it + 360 else it }
        return ra to Math.toDegrees(asin(y))
    }

    @Test
    fun `unrotated art spans its width in RA and its height in Dec`() {
        val out = FloatArray(12)
        DsoArtGeometry.corners(raDeg = 10.0, decDeg = 0.0, widthDeg = 2.0, heightOverWidth = 0.5, rotationDeg = 0.0, out)
        // Top-left: u = -1 deg -> RA + 1 (east is left on the sky), v = +0.5.
        val (ra0, dec0) = raDec(out, 0)
        assertEquals(11.0, ra0, 1e-4)
        assertEquals(0.5, dec0, 1e-4)
        val (ra2, dec2) = raDec(out, 2)
        assertEquals(9.0, ra2, 1e-4)
        assertEquals(-0.5, dec2, 1e-4)
    }

    @Test
    fun `RA offsets widen with declination`() {
        val out = FloatArray(12)
        DsoArtGeometry.corners(raDeg = 100.0, decDeg = 60.0, widthDeg = 1.0, heightOverWidth = 1.0, rotationDeg = 0.0, out)
        val (ra1, _) = raDec(out, 1)
        assertEquals(100.0 - 0.5 / 0.5, ra1, 1e-3)
    }

    @Test
    fun `a quarter turn swaps the axes`() {
        val out = FloatArray(12)
        DsoArtGeometry.corners(raDeg = 50.0, decDeg = 0.0, widthDeg = 2.0, heightOverWidth = 0.5, rotationDeg = 90.0, out)
        // u = -1, v = 0.5 -> uRot = -0.5, vRot = -1.
        val (ra0, dec0) = raDec(out, 0)
        assertEquals(50.5, ra0, 1e-4)
        assertEquals(-1.0, dec0, 1e-4)
    }

    @Test
    fun `art width falls back to the object size`() {
        assertEquals(1.5, DsoArtGeometry.widthDeg(1.5f, 10f), 0.0)
        assertEquals(0.5, DsoArtGeometry.widthDeg(0f, 30f), 1e-9)
        assertEquals(0.1, DsoArtGeometry.widthDeg(0f, 1f), 1e-9)
    }
}
