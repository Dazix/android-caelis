package com.dazix.caelis.domain.arframe

import android.view.Surface
import com.dazix.caelis.ar.CameraProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SkyProjectorTest {

    private val w = 1080f
    private val h = 2400f
    private val fov = 56f

    /** Row-major device -> ENU: phone upright, back camera facing north (device Y = Up, Z = South). */
    private val uprightNorth = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 0f, -1f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f,
    )

    private fun projector(sensor: FloatArray, rotationDegrees: Int, width: Float = w, height: Float = h): SkyProjector {
        val model = FloatArray(16)
        SkyFrameMath.cameraModelMatrix(sensor, rotationDegrees, model)
        return SkyProjector().also { it.update(model, width, height, fov) }
    }

    private fun enu(azDeg: Double, elDeg: Double): FloatArray {
        val az = Math.toRadians(azDeg)
        val el = Math.toRadians(elDeg)
        return floatArrayOf((sin(az) * cos(el)).toFloat(), (cos(az) * cos(el)).toFloat(), sin(el).toFloat())
    }

    @Test
    fun `the direction the camera faces lands in the screen centre`() {
        val p = projector(uprightNorth, 0)
        val out = FloatArray(3)
        val north = enu(0.0, 0.0)
        assertTrue(p.projectEnu(north[0], north[1], north[2], out))
        assertEquals(w / 2, out[0], 0.01f)
        assertEquals(h / 2, out[1], 0.01f)
    }

    @Test
    fun `east is right and up is up in portrait`() {
        val p = projector(uprightNorth, 0)
        val out = FloatArray(3)
        val east = enu(10.0, 0.0)
        p.projectEnu(east[0], east[1], east[2], out)
        assertTrue("10 deg east must be right of centre", out[0] > w / 2)
        val up = enu(0.0, 10.0)
        p.projectEnu(up[0], up[1], up[2], out)
        assertTrue("10 deg up must be above centre", out[1] < h / 2)
    }

    @Test
    fun `half the horizontal FOV reaches exactly the screen edge`() {
        val p = projector(uprightNorth, 0)
        val out = FloatArray(3)
        val edge = enu(fov / 2.0, 0.0)
        p.projectEnu(edge[0], edge[1], edge[2], out)
        assertEquals(w, out[0], 0.5f)
    }

    @Test
    fun `agrees with the Canvas pipeline in natural and upside-down orientation`() {
        // A tilted, turned pose so every matrix element matters.
        val sensor = FloatArray(16)
        val a = Math.toRadians(35.0)
        val b = Math.toRadians(-20.0)
        // Row-major R = Rz(a) * Rx(b) built by hand (android.opengl.Matrix is stubbed in JVM tests).
        val rz = arrayOf(doubleArrayOf(cos(a), -sin(a), 0.0), doubleArrayOf(sin(a), cos(a), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(b), -sin(b)), doubleArrayOf(0.0, sin(b), cos(b)))
        for (i in 0 until 3) for (j in 0 until 3) {
            sensor[i * 4 + j] = (0 until 3).sumOf { k -> rz[i][k] * rx[k][j] }.toFloat()
        }
        sensor[15] = 1f

        val cases = mapOf(0 to Surface.ROTATION_0, 180 to Surface.ROTATION_180)
        val gpu = FloatArray(3)
        val cpu = FloatArray(3)
        for ((degrees, surfaceRotation) in cases) {
            val p = projector(sensor, degrees)
            for (az in doubleArrayOf(20.0, 35.0, 60.0)) for (el in doubleArrayOf(-50.0, -30.0, -10.0)) {
                val d = enu(az, el)
                val a1 = p.projectEnu(d[0], d[1], d[2], gpu)
                val a2 = CameraProjection.projectFast(
                    az, el, sensor, w, h, surfaceRotation, fov, cpu,
                )
                assertEquals("in front, az $az el $el rot $degrees", a2, a1)
                if (a1) {
                    assertEquals("x, az $az el $el rot $degrees", cpu[0], gpu[0], 0.5f)
                    assertEquals("y, az $az el $el rot $degrees", cpu[1], gpu[1], 0.5f)
                }
            }
        }
    }

    @Test
    fun `landscape with the top edge to the left puts east on the right`() {
        // ROTATION_90 = device turned 90 deg counter-clockwise (Display.getRotation docs): top edge
        // points west when the camera faces north, device X points up.
        val landscape = floatArrayOf(
            0f, -1f, 0f, 0f,
            0f, 0f, -1f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )
        val p = projector(landscape, 90, width = h, height = w)
        val out = FloatArray(3)
        val north = enu(0.0, 0.0)
        assertTrue(p.projectEnu(north[0], north[1], north[2], out))
        assertEquals(h / 2, out[0], 0.01f)
        assertEquals(w / 2, out[1], 0.01f)
        val east = enu(10.0, 0.0)
        p.projectEnu(east[0], east[1], east[2], out)
        assertTrue("east must be right of centre, was ${out[0]}", out[0] > h / 2)
        val up = enu(0.0, 10.0)
        p.projectEnu(up[0], up[1], up[2], out)
        assertTrue("up must be above centre, was ${out[1]}", out[1] < w / 2)
    }

    @Test
    fun `behind the camera is reported and mirrored for the off-screen bearing`() {
        val p = projector(uprightNorth, 0)
        val out = FloatArray(3)
        val southEast = enu(170.0, 0.0)
        assertFalse(p.projectEnu(southEast[0], southEast[1], southEast[2], out))
        assertTrue(out[2] < 0f)
        // 170 deg is 10 deg short of due south going clockwise: behind and to the right -> mirrored left.
        assertTrue(out[0] < w / 2)
    }

    @Test
    fun `unproject inverts project across the screen`() {
        val p = projector(uprightNorth, 90, width = h, height = w)
        val dir = FloatArray(3)
        val out = FloatArray(3)
        for (sx in floatArrayOf(0f, 400f, 1200f, h)) for (sy in floatArrayOf(0f, 300f, w)) {
            p.unproject(sx, sy, dir)
            assertTrue(p.projectEnu(dir[0], dir[1], dir[2], out))
            assertEquals(sx, out[0], 0.05f)
            assertEquals(sy, out[1], 0.05f)
        }
    }

    @Test
    fun `celestial projection matches projecting the ENU image of the star`() {
        val p = projector(uprightNorth, 0)
        val c2e = FloatArray(16)
        SkyFrameMath.celestialToEnu(80.0, 49.0, c2e)
        val star = floatArrayOf(0.2f, 0.7f, 0.68f)
        val enuStar = FloatArray(3)
        SkyFrameMath.transformDirection(c2e, star[0], star[1], star[2], enuStar)
        val a = FloatArray(3)
        val b = FloatArray(3)
        p.projectCelestial(c2e, star[0], star[1], star[2], a)
        p.projectEnu(enuStar[0], enuStar[1], enuStar[2], b)
        for (i in 0 until 3) assertEquals(b[i], a[i], 1e-3f)
    }
}
