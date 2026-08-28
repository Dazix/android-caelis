package com.dazix.caelis.domain.nightsky

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class StarFrustumCullingTest {

    private val fovH = 56f
    private val viewW = 1080f
    private val viewH = 2400f

    @Test
    fun `verifies star directly in camera center is in frustum`() {
        val threshold = StarFrustumCulling.computeFrustumDotThreshold(fovH, viewW, viewH)

        // Camera pointing North ([0, 1, 0] in ENU)
        val inFrustum = StarFrustumCulling.isStarInFrustum(
            camFwdX = 0f,
            camFwdY = 1f,
            camFwdZ = 0f,
            starVx = 0f,
            starVy = 1f,
            starVz = 0f,
            dotThreshold = threshold,
        )

        assertTrue(inFrustum)
    }

    @Test
    fun `verifies star behind camera is culled`() {
        val threshold = StarFrustumCulling.computeFrustumDotThreshold(fovH, viewW, viewH)

        // Camera pointing North ([0, 1, 0]), star is South ([0, -1, 0])
        val inFrustum = StarFrustumCulling.isStarInFrustum(
            camFwdX = 0f,
            camFwdY = 1f,
            camFwdZ = 0f,
            starVx = 0f,
            starVy = -1f,
            starVz = 0f,
            dotThreshold = threshold,
        )

        assertFalse(inFrustum)
    }

    @Test
    fun `verifies star slightly off-center within FOV is in frustum`() {
        val threshold = StarFrustumCulling.computeFrustumDotThreshold(fovH, viewW, viewH)

        // Star 15° East of North
        val rad15 = Math.toRadians(15.0)
        val vx = kotlin.math.sin(rad15).toFloat()
        val vy = kotlin.math.cos(rad15).toFloat()

        val inFrustum = StarFrustumCulling.isStarInFrustum(
            camFwdX = 0f,
            camFwdY = 1f,
            camFwdZ = 0f,
            starVx = vx,
            starVy = vy,
            starVz = 0f,
            dotThreshold = threshold,
        )

        assertTrue(inFrustum)
    }

    @Test
    fun `verifies star 80 degrees away is culled`() {
        val threshold = StarFrustumCulling.computeFrustumDotThreshold(fovH, viewW, viewH)

        // Star 80° East of North
        val rad80 = Math.toRadians(80.0)
        val vx = kotlin.math.sin(rad80).toFloat()
        val vy = kotlin.math.cos(rad80).toFloat()

        val inFrustum = StarFrustumCulling.isStarInFrustum(
            camFwdX = 0f,
            camFwdY = 1f,
            camFwdZ = 0f,
            starVx = vx,
            starVy = vy,
            starVz = 0f,
            dotThreshold = threshold,
        )

        assertFalse(inFrustum)
    }
}
