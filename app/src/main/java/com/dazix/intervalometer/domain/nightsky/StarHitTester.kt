package com.dazix.intervalometer.domain.nightsky

/**
 * High-performance 2D screen-space hit testing for celestial objects.
 * Pure Kotlin, zero heap allocations, O(N) linear sweep with early rejection.
 */
object StarHitTester {

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
