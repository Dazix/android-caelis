package com.dazix.intervalometer.domain.nightsky

/**
 * High-performance 2D screen-space hit testing for celestial objects (Constellations and Stars).
 * Pure Kotlin, zero heap allocations, O(N) linear sweep with early rejection.
 */
object StarHitTester {

    /**
     * Hit tests animated constellation name text in 2D screen space (Priority 1).
     *
     * @param tapX Touch X coordinate in viewport pixels.
     * @param tapY Touch Y coordinate in viewport pixels.
     * @param centroidScreenX Pre-projected screen X coordinates for constellation centroids.
     * @param centroidScreenY Pre-projected screen Y coordinates for constellation centroids.
     * @param centroidInFront Boolean array indicating whether constellation centroid is in front of camera.
     * @param constellationProgress Animation progress float array [0.0f, 1.0f] for each constellation.
     * @param totalConstellations Total count of constellations to test.
     * @param textOffsetYPx Base vertical slide offset in pixels (e.g. 40dp * density).
     * @param halfWidthPx Half-width radius of elliptical hit area in pixels (e.g. 75dp * density).
     * @param halfHeightPx Half-height radius of elliptical hit area in pixels (e.g. 30dp * density).
     * @param minProgress Minimum animation reveal progress required for interactive hit testing (default 0.3f).
     * @return Constellation index [0, totalConstellations - 1], or -1 if no constellation text was hit.
     */
    fun findConstellationTextHit(
        tapX: Float,
        tapY: Float,
        centroidScreenX: FloatArray,
        centroidScreenY: FloatArray,
        centroidInFront: BooleanArray,
        constellationProgress: FloatArray,
        totalConstellations: Int,
        textOffsetYPx: Float,
        halfWidthPx: Float,
        halfHeightPx: Float,
        minProgress: Float = 0.3f,
    ): Int {
        if (totalConstellations <= 0 || halfWidthPx <= 0f || halfHeightPx <= 0f) return -1

        val limit = minOf(
            totalConstellations,
            centroidScreenX.size,
            centroidScreenY.size,
            centroidInFront.size,
            constellationProgress.size,
        )

        val invRx2 = 1.0f / (halfWidthPx * halfWidthPx)
        val invRy2 = 1.0f / (halfHeightPx * halfHeightPx)

        for (i in 0 until limit) {
            val progress = constellationProgress[i]
            if (progress <= minProgress || !centroidInFront[i]) continue

            val cx = centroidScreenX[i]
            val cy = centroidScreenY[i] - (textOffsetYPx * (1.0f - progress))

            val dx = tapX - cx
            val dy = tapY - cy

            val ellipticalDistSq = (dx * dx * invRx2) + (dy * dy * invRy2)
            if (ellipticalDistSq <= 1.0f) {
                return i
            }
        }

        return -1
    }

    /**
     * Finds the index of the closest visible star to the user's tap coordinates within a maximum touch radius.
     *
     * @param tapX Touch X coordinate in viewport pixels.
     * @param tapY Touch Y coordinate in viewport pixels.
     * @param starScreenX Pre-projected screen X coordinates array.
     * @param starScreenY Pre-projected screen Y coordinates array.
     * @param starInFront Boolean array tracking whether each star is in front of the camera plane (zCamera > 0).
     * @param totalStars Total count of stars in the catalog to search.
     * @param maxRadiusPx Maximum touch hit radius in pixels (e.g. 48dp converted to px).
     * @return Star index [0, totalStars - 1] of the closest star within radius, or -1 if no star was hit.
     */
    fun findClosestVisibleStar(
        tapX: Float,
        tapY: Float,
        starScreenX: FloatArray,
        starScreenY: FloatArray,
        starInFront: BooleanArray,
        totalStars: Int,
        maxRadiusPx: Float,
    ): Int {
        if (totalStars <= 0 || maxRadiusPx <= 0.0f) return -1

        var closestIndex = -1
        var minDistanceSq = maxRadiusPx * maxRadiusPx
        val limit = minOf(totalStars, starScreenX.size, starScreenY.size, starInFront.size)

        for (i in 0 until limit) {
            if (!starInFront[i]) continue

            val dx = tapX - starScreenX[i]
            val dy = tapY - starScreenY[i]
            val distSq = dx * dx + dy * dy

            if (distSq < minDistanceSq) {
                minDistanceSq = distSq
                closestIndex = i
            }
        }

        return closestIndex
    }
}
