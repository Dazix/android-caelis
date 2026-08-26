package com.dazix.intervalometer.domain.nightsky

/**
 * High-performance 2D screen-space hit testing for celestial objects (Constellations and Stars).
 * Pure Kotlin, zero heap allocations, O(N) linear sweep with early rejection.
 */
object StarHitTester {

    /**
     * Calculates the dynamic hit target radius in pixels based on the planet's angular diameter,
     * perspective distance, the "Planetarium Trick" visual boost factor, and minimum touch target size.
     *
     * @param angularDiameterDeg Apparent angular diameter in degrees.
     * @param isMoon True if body is the Moon (exempt from artificial size boost).
     * @param effectiveFocalLengthPx Effective camera focal length in pixels.
     * @param zCamera Distance along camera optical axis (positive is in front).
     * @param minTouchRadiusPx Minimum touch radius in pixels (e.g. 48dp * density).
     * @return Hit radius in screen pixels.
     */
    fun computePlanetHitRadiusPx(
        angularDiameterDeg: Float,
        isMoon: Boolean,
        effectiveFocalLengthPx: Float,
        zCamera: Float = 1.0f,
        minTouchRadiusPx: Float = 48f,
    ): Float {
        val safeAngularDiameter = if (angularDiameterDeg > 0f) angularDiameterDeg else 0.01f
        val angularSizeRad = Math.toRadians(safeAngularDiameter.toDouble()).toFloat()
        val boostFactor = if (!isMoon) (0.013f / safeAngularDiameter).coerceIn(1.0f, 10.0f) else 1.0f
        val z = if (zCamera > 0.001f) zCamera else 1.0f
        val renderedRadiusPx = ((angularSizeRad * boostFactor) * (effectiveFocalLengthPx / z)) / 2f
        return maxOf(120f, minTouchRadiusPx, renderedRadiusPx)
    }

    /**
     * Priority 0 hit testing for dynamic Planets and Moon using pre-projected screen coordinates.
     * Zero heap allocations, O(N) sweep across visible active planets.
     *
     * @param tapX Touch X coordinate in viewport pixels.
     * @param tapY Touch Y coordinate in viewport pixels.
     * @param planetScreenX Pre-projected screen X coordinates array.
     * @param planetScreenY Pre-projected screen Y coordinates array.
     * @param planetRadiusPx Rendered/dynamic radius array for each planet in pixels.
     * @param planetInFront Boolean array tracking whether each planet is in front of the camera.
     * @param totalPlanets Total count of active planets in the buffer.
     * @param planets List of active [PlanetState] objects corresponding to buffer indices.
     * @param minTouchRadiusPx Minimum touch target radius in pixels (e.g. 48dp * density).
     * @return Closest [PlanetState] intersecting the tap area, or null if missed.
     */
    fun findPlanetHit(
        tapX: Float,
        tapY: Float,
        planetScreenX: FloatArray,
        planetScreenY: FloatArray,
        planetRadiusPx: FloatArray,
        planetInFront: BooleanArray,
        totalPlanets: Int,
        planets: List<PlanetState>,
        minTouchRadiusPx: Float,
    ): PlanetState? {
        if (totalPlanets <= 0 || minTouchRadiusPx <= 0.0f || planets.isEmpty()) return null

        var closestPlanet: PlanetState? = null
        var minDistanceSq = Float.MAX_VALUE
        val limit = minOf(
            totalPlanets,
            planetScreenX.size,
            planetScreenY.size,
            planetRadiusPx.size,
            planetInFront.size,
            planets.size,
        )

        for (i in 0 until limit) {
            if (!planetInFront[i]) continue

            val px = planetScreenX[i]
            val py = planetScreenY[i]
            val hitRadius = maxOf(120f, minTouchRadiusPx, planetRadiusPx[i])
            val dx = tapX - px
            val dy = tapY - py
            val distanceSq = dx * dx + dy * dy

            if (distanceSq <= hitRadius * hitRadius) {
                if (distanceSq < minDistanceSq) {
                    minDistanceSq = distanceSq
                    closestPlanet = planets[i]
                }
            }
        }

        return closestPlanet
    }

    /**
     * Standalone Priority 0 hit testing for dynamic Planets and Moon using camera orientation matrix,
     * sidereal time, and observer latitude.
     *
     * @param touchX Touch X coordinate in viewport pixels.
     * @param touchY Touch Y coordinate in viewport pixels.
     * @param currentPlanets List of [PlanetState]s to test.
     * @param matrix 4x4 or 3x3 rotation matrix (world to device).
     * @param lstDegrees Local Sidereal Time in degrees [0.0, 360.0).
     * @param latitudeDegrees Observer latitude in degrees [-90.0, +90.0].
     * @param viewportWidth Screen width in pixels.
     * @param viewportHeight Screen height in pixels.
     * @param displayRotation Screen rotation (0 for Surface.ROTATION_0, 1 for ROTATION_90, 2 for ROTATION_180, 3 for ROTATION_270).
     * @param effectiveFocalLengthPx Effective camera focal length in pixels.
     * @param minTouchRadiusPx Minimum touch radius in pixels (e.g. 48dp * density).
     * @param tempWorldVec Optional pre-allocated FloatArray of size at least 3 to avoid allocations.
     * @return Closest [PlanetState] intersecting the tap, or null if missed.
     */
    fun findPlanetHit(
        touchX: Float,
        touchY: Float,
        currentPlanets: List<PlanetState>,
        matrix: FloatArray,
        lstDegrees: Double,
        latitudeDegrees: Double,
        viewportWidth: Float,
        viewportHeight: Float,
        displayRotation: Int = 0,
        effectiveFocalLengthPx: Float,
        minTouchRadiusPx: Float = 48f,
        tempWorldVec: FloatArray? = null,
    ): PlanetState? {
        if (currentPlanets.isEmpty() || matrix.isEmpty() || minTouchRadiusPx <= 0f) return null

        val worldVec = tempWorldVec ?: FloatArray(3)
        val halfW = viewportWidth * 0.5f
        val halfH = viewportHeight * 0.5f

        var closestPlanet: PlanetState? = null
        var minDistanceSq = Float.MAX_VALUE

        for (planet in currentPlanets) {
            EquatorialCoordinates.computeWorldVector(
                planet.raDegrees,
                planet.decDegrees,
                lstDegrees,
                latitudeDegrees,
                worldVec,
                0,
            )
            val vx = worldVec[0]
            val vy = worldVec[1]
            val vz = worldVec[2]

            // Transform World -> Device coordinates: V_dev = R^T * V_world
            val xDev = if (matrix.size >= 16) {
                matrix[0] * vx + matrix[4] * vy + matrix[8] * vz
            } else if (matrix.size >= 9) {
                matrix[0] * vx + matrix[3] * vy + matrix[6] * vz
            } else vx

            val yDev = if (matrix.size >= 16) {
                matrix[1] * vx + matrix[5] * vy + matrix[9] * vz
            } else if (matrix.size >= 9) {
                matrix[1] * vx + matrix[4] * vy + matrix[7] * vz
            } else vy

            val zDev = if (matrix.size >= 16) {
                matrix[2] * vx + matrix[6] * vy + matrix[10] * vz
            } else if (matrix.size >= 9) {
                matrix[2] * vx + matrix[5] * vy + matrix[8] * vz
            } else vz

            val xs: Float
            val ys: Float
            when (displayRotation) {
                0 -> { xs = xDev; ys = yDev }
                1 -> { xs = yDev; ys = -xDev }
                2 -> { xs = -xDev; ys = -yDev }
                3 -> { xs = -yDev; ys = xDev }
                else -> { xs = xDev; ys = yDev }
            }
            val zs = -zDev

            if (zs > 0.001f) {
                val pX = halfW + effectiveFocalLengthPx * (xs / zs)
                val pY = halfH - effectiveFocalLengthPx * (ys / zs)

                val dx = touchX - pX
                val dy = touchY - pY
                val distanceSq = dx * dx + dy * dy

                val hitRadiusPx = computePlanetHitRadiusPx(
                    angularDiameterDeg = planet.angularDiameterDeg,
                    isMoon = planet.isMoon || planet.id.equals("Moon", ignoreCase = true),
                    effectiveFocalLengthPx = effectiveFocalLengthPx,
                    zCamera = zs,
                    minTouchRadiusPx = minTouchRadiusPx,
                )

                if (distanceSq <= hitRadiusPx * hitRadiusPx) {
                    if (distanceSq < minDistanceSq) {
                        minDistanceSq = distanceSq
                        closestPlanet = planet
                    }
                }
            }
        }

        return closestPlanet
    }

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
