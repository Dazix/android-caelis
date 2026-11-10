package com.dazix.caelis.domain.nightsky

import com.dazix.caelis.data.nightsky.NightSkyCatalog
import kotlin.math.pow

/**
 * High-performance 2D screen-space hit testing for celestial objects (Constellations and Stars).
 * Pure Kotlin, zero heap allocations, O(N) linear sweep with early rejection.
 */
object StarHitTester {

    /**
     * Checks if a celestial body at Earth ENU coordinates ([vx], [vy], [vz]) is occluded
     * behind the ground landscape or terrain horizon.
     *
     * @param vx East component of World ENU unit vector.
     * @param vy North component of World ENU unit vector.
     * @param vz Up component of World ENU unit vector.
     * @param isLandscapeVisible True if the photorealistic ground landscape panorama is enabled.
     * @param useRealHorizon True if real DEM terrain horizon profile is enabled.
     * @param horizonProfile Active [com.dazix.caelis.domain.horizon.HorizonProfile] if available.
     * @param alignAzimuthOffsetDeg Alignment compass azimuth offset in degrees.
     * @param alignElevationOffsetDeg Alignment elevation pitch offset in degrees.
     * @return True if the celestial body is occluded behind the landscape horizon, false if visible in open sky.
     */
    fun isOccludedByLandscape(
        vx: Float,
        vy: Float,
        vz: Float,
        isLandscapeVisible: Boolean,
        useRealHorizon: Boolean,
        horizonProfile: com.dazix.caelis.domain.horizon.HorizonProfile?,
        alignAzimuthOffsetDeg: Float = 0f,
        alignElevationOffsetDeg: Float = 0f,
    ): Boolean {
        if (!isLandscapeVisible && !(useRealHorizon && horizonProfile != null && horizonProfile.isUsable)) {
            return false
        }

        if (useRealHorizon && horizonProfile != null && horizonProfile.isUsable) {
            val rawAzimuthDeg = Math.toDegrees(kotlin.math.atan2(vx.toDouble(), vy.toDouble()))
            val normAzimuthDeg = if (rawAzimuthDeg < 0.0) rawAzimuthDeg + 360.0 else rawAzimuthDeg
            val alignedAzimuthDeg = (normAzimuthDeg + alignAzimuthOffsetDeg).let {
                val m = it % 360.0
                if (m < 0.0) m + 360.0 else m
            }
            val rawElevationDeg = Math.toDegrees(kotlin.math.asin(vz.coerceIn(-1.0f, 1.0f).toDouble()))
            val alignedElevationDeg = rawElevationDeg + alignElevationOffsetDeg
            val terrainAlt = horizonProfile.elevationAt(alignedAzimuthDeg)
            return alignedElevationDeg < terrainAlt
        }

        if (isLandscapeVisible) {
            val rawElevationDeg = Math.toDegrees(kotlin.math.asin(vz.coerceIn(-1.0f, 1.0f).toDouble()))
            val alignedElevationDeg = rawElevationDeg + alignElevationOffsetDeg
            return alignedElevationDeg < 0.0
        }

        return false
    }

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
     * @param labelShiftYPx Where the last drawn frame moved each name from its own spot (a line down
     *   or up, `LabelDeclutter`), in pixels, Y down; null when nothing was drawn yet.
     * @param labelHidden Names the last drawn frame did not draw (switched off, or left out by the
     *   declutter); null when nothing was drawn yet.
     * @return Constellation index [0, totalConstellations - 1], or -1 if no constellation text was hit.
     *
     * Of several names whose areas take the tap, the nearest wins. The first in catalogue order used
     * to, and as constellations come before asterisms, an asterism inside its constellation (Job's
     * Coffin in Delphinus, the Northern Cross in Cygnus) could not be tapped until a zoom pulled the
     * two names apart.
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
        centroidWorldX: FloatArray? = null,
        centroidWorldY: FloatArray? = null,
        centroidWorldZ: FloatArray? = null,
        isLandscapeVisible: Boolean = false,
        useRealHorizon: Boolean = false,
        horizonProfile: com.dazix.caelis.domain.horizon.HorizonProfile? = null,
        alignAzimuthOffsetDeg: Float = 0f,
        alignElevationOffsetDeg: Float = 0f,
        labelShiftYPx: FloatArray? = null,
        labelHidden: BooleanArray? = null,
    ): Int {
        if (totalConstellations <= 0 || halfWidthPx <= 0f || halfHeightPx <= 0f) return -1

        val limit = minOf(
            totalConstellations,
            centroidScreenX.size,
            centroidScreenY.size,
            centroidInFront.size,
            constellationProgress.size,
        )

        val checkOcclusion = (isLandscapeVisible || (useRealHorizon && horizonProfile != null && horizonProfile.isUsable)) &&
            centroidWorldX != null && centroidWorldY != null && centroidWorldZ != null

        val invRx2 = 1.0f / (halfWidthPx * halfWidthPx)
        val invRy2 = 1.0f / (halfHeightPx * halfHeightPx)

        var best = -1
        var bestDistSq = Float.MAX_VALUE
        for (i in 0 until limit) {
            val progress = constellationProgress[i]
            if (progress <= minProgress || !centroidInFront[i]) continue
            if (labelHidden != null && i < labelHidden.size && labelHidden[i]) continue

            if (checkOcclusion && isOccludedByLandscape(
                    centroidWorldX[i],
                    centroidWorldY[i],
                    centroidWorldZ[i],
                    isLandscapeVisible,
                    useRealHorizon,
                    horizonProfile,
                    alignAzimuthOffsetDeg,
                    alignElevationOffsetDeg,
                )
            ) {
                continue
            }

            val cx = centroidScreenX[i]
            val shift = if (labelShiftYPx != null && i < labelShiftYPx.size) labelShiftYPx[i] else 0f
            val cy = centroidScreenY[i] - (textOffsetYPx * (1.0f - progress)) + shift

            val dx = tapX - cx
            val dy = tapY - cy

            val ellipticalDistSq = (dx * dx * invRx2) + (dy * dy * invRy2)
            if (ellipticalDistSq <= 1.0f && ellipticalDistSq < bestDistSq) {
                best = i
                bestDistSq = ellipticalDistSq
            }
        }

        return best
    }

    /**
     * Calculates a perceptual brightness weight based on astronomical apparent magnitude.
     * Brighter objects (lower or negative magnitude) receive higher weights.
     *
     * Example weights:
     * - Sirius (mag -1.46): ~13.5
     * - Vega (mag 0.0): ~8.0
     * - Polaris (mag 2.0): ~4.0
     * - Mag 4.0: ~2.0
     * - Mag 6.0 (naked eye limit): ~1.0
     * - Mag 8.0: ~0.5
     * - Mag 10.0+: ~0.25
     */
    fun computeMagnitudeWeight(magnitude: Float): Float {
        val clampedMag = magnitude.coerceIn(-5.0f, 15.0f)
        return 2.0f.pow((6.0f - clampedMag) * 0.5f)
    }

    /**
     * Calculates a weighted distance-magnitude score for celestial hit testing.
     * Lower score represents a better hit candidate (closer distance and brighter magnitude).
     *
     * @param distanceSq Squared distance from tap to projected coordinates in screen pixels.
     * @param magnitude Apparent astronomical magnitude.
     * @param epsilon Constant offset in px^2 to prevent 0-distance division artifacts.
     */
    fun computeHitScore(distanceSq: Float, magnitude: Float, epsilon: Float = 25f): Float {
        val weight = computeMagnitudeWeight(magnitude)
        return (distanceSq + epsilon) / weight
    }

    /**
     * Inserts a candidate into a pre-allocated fixed-capacity top-K array sorted ascending by score.
     * Zero heap allocations.
     *
     * @param candidateIndex Item index to insert.
     * @param score Lower is better.
     * @param outIndices Pre-allocated IntArray to hold candidate indices.
     * @param outScores Pre-allocated FloatArray to hold candidate scores.
     * @param currentCount Number of valid candidates currently in the buffer.
     * @param maxCapacity Maximum capacity (e.g. 5).
     * @return New count of valid candidates (up to maxCapacity).
     */
    fun insertCandidate(
        candidateIndex: Int,
        score: Float,
        outIndices: IntArray,
        outScores: FloatArray,
        currentCount: Int,
        maxCapacity: Int = outIndices.size,
    ): Int {
        var insertPos = currentCount
        for (i in 0 until currentCount) {
            if (score < outScores[i]) {
                insertPos = i
                break
            }
        }
        if (insertPos >= maxCapacity) {
            return currentCount
        }
        val newCount = minOf(currentCount + 1, maxCapacity)
        for (i in newCount - 1 downTo insertPos + 1) {
            outIndices[i] = outIndices[i - 1]
            outScores[i] = outScores[i - 1]
        }
        outIndices[insertPos] = candidateIndex
        outScores[insertPos] = score
        return newCount
    }

    /**
     * Collects top candidate visible stars within [maxRadiusPx] sorted by weighted distance-magnitude score.
     * Zero heap allocations.
     *
     * @param tapX Touch X coordinate in viewport pixels.
     * @param tapY Touch Y coordinate in viewport pixels.
     * @param starScreenX Pre-projected screen X coordinates array.
     * @param starScreenY Pre-projected screen Y coordinates array.
     * @param starInFront Boolean array tracking whether each star is in front of the camera plane (zCamera > 0).
     * @param starMagnitudes Apparent magnitudes array (or null to use default magnitude).
     * @param totalStars Total count of stars in the catalog.
     * @param maxRadiusPx Maximum touch hit radius in pixels (e.g. 48dp converted to px).
     * @param outIndices Pre-allocated IntArray to store result star indices.
     * @param outScores Pre-allocated FloatArray to store result star scores.
     * @param maxCandidates Maximum number of candidates to return (default is outIndices.size).
     * @return Number of candidate stars placed into [outIndices] and [outScores].
     */
    fun findTopVisibleStars(
        tapX: Float,
        tapY: Float,
        starScreenX: FloatArray,
        starScreenY: FloatArray,
        starInFront: BooleanArray,
        starMagnitudes: FloatArray?,
        totalStars: Int,
        maxRadiusPx: Float,
        outIndices: IntArray,
        outScores: FloatArray,
        maxCandidates: Int = minOf(outIndices.size, outScores.size),
        starWorldX: FloatArray? = null,
        starWorldY: FloatArray? = null,
        starWorldZ: FloatArray? = null,
        isLandscapeVisible: Boolean = false,
        useRealHorizon: Boolean = false,
        horizonProfile: com.dazix.caelis.domain.horizon.HorizonProfile? = null,
        alignAzimuthOffsetDeg: Float = 0f,
        alignElevationOffsetDeg: Float = 0f,
    ): Int {
        if (totalStars <= 0 || maxRadiusPx <= 0.0f || maxCandidates <= 0) return 0

        val maxRadiusSq = maxRadiusPx * maxRadiusPx
        val limit = minOf(totalStars, starScreenX.size, starScreenY.size, starInFront.size)
        var candidateCount = 0

        val checkOcclusion = (isLandscapeVisible || (useRealHorizon && horizonProfile != null && horizonProfile.isUsable)) &&
            starWorldX != null && starWorldY != null && starWorldZ != null

        for (i in 0 until limit) {
            if (!starInFront[i]) continue

            if (checkOcclusion && isOccludedByLandscape(
                    starWorldX[i],
                    starWorldY[i],
                    starWorldZ[i],
                    isLandscapeVisible,
                    useRealHorizon,
                    horizonProfile,
                    alignAzimuthOffsetDeg,
                    alignElevationOffsetDeg,
                )
            ) {
                continue
            }

            val dx = tapX - starScreenX[i]
            val dy = tapY - starScreenY[i]
            val distSq = dx * dx + dy * dy

            if (distSq <= maxRadiusSq) {
                val mag = starMagnitudes?.getOrNull(i) ?: 4.0f
                val score = computeHitScore(distSq, mag)
                candidateCount = insertCandidate(
                    candidateIndex = i,
                    score = score,
                    outIndices = outIndices,
                    outScores = outScores,
                    currentCount = candidateCount,
                    maxCapacity = maxCandidates,
                )
            }
        }

        return candidateCount
    }

    /**
     * Collects top candidate visible stars directly reading magnitudes from FlatBuffers catalog without allocations.
     * Zero heap allocations.
     */
    fun findTopStarsFromCatalog(
        tapX: Float,
        tapY: Float,
        starScreenX: FloatArray,
        starScreenY: FloatArray,
        starInFront: BooleanArray,
        catalog: NightSkyCatalog,
        totalStars: Int,
        maxRadiusPx: Float,
        outIndices: IntArray,
        outScores: FloatArray,
        maxCandidates: Int = minOf(outIndices.size, outScores.size),
        starWorldX: FloatArray? = null,
        starWorldY: FloatArray? = null,
        starWorldZ: FloatArray? = null,
        isLandscapeVisible: Boolean = false,
        useRealHorizon: Boolean = false,
        horizonProfile: com.dazix.caelis.domain.horizon.HorizonProfile? = null,
        alignAzimuthOffsetDeg: Float = 0f,
        alignElevationOffsetDeg: Float = 0f,
    ): Int {
        if (totalStars <= 0 || maxRadiusPx <= 0.0f || maxCandidates <= 0) return 0

        val bb = catalog.byteBuffer
        val starsVectorOffset = catalog.starsVectorOffset()
        val maxRadiusSq = maxRadiusPx * maxRadiusPx
        val limit = minOf(totalStars, starScreenX.size, starScreenY.size, starInFront.size)
        var candidateCount = 0

        val checkOcclusion = (isLandscapeVisible || (useRealHorizon && horizonProfile != null && horizonProfile.isUsable)) &&
            starWorldX != null && starWorldY != null && starWorldZ != null

        for (i in 0 until limit) {
            if (!starInFront[i]) continue

            if (checkOcclusion && isOccludedByLandscape(
                    starWorldX[i],
                    starWorldY[i],
                    starWorldZ[i],
                    isLandscapeVisible,
                    useRealHorizon,
                    horizonProfile,
                    alignAzimuthOffsetDeg,
                    alignElevationOffsetDeg,
                )
            ) {
                continue
            }

            val dx = tapX - starScreenX[i]
            val dy = tapY - starScreenY[i]
            val distSq = dx * dx + dy * dy

            if (distSq <= maxRadiusSq) {
                val mag = bb.getFloat(starsVectorOffset + i * 12 + 8)
                val score = computeHitScore(distSq, mag)
                candidateCount = insertCandidate(
                    candidateIndex = i,
                    score = score,
                    outIndices = outIndices,
                    outScores = outScores,
                    currentCount = candidateCount,
                    maxCapacity = maxCandidates,
                )
            }
        }

        return candidateCount
    }

    /**
     * Collects top candidate visible Deep Sky Objects intersecting the tap area sorted by score.
     * Zero heap allocations.
     */
    fun findTopDsos(
        tapX: Float,
        tapY: Float,
        dsoScreenX: FloatArray,
        dsoScreenY: FloatArray,
        dsoRadiusPx: FloatArray,
        dsoInFront: BooleanArray,
        dsoMagnitudes: FloatArray?,
        totalDsos: Int,
        minTouchRadiusPx: Float,
        outIndices: IntArray,
        outScores: FloatArray,
        maxCandidates: Int = minOf(outIndices.size, outScores.size),
        dsoWorldX: FloatArray? = null,
        dsoWorldY: FloatArray? = null,
        dsoWorldZ: FloatArray? = null,
        isLandscapeVisible: Boolean = false,
        useRealHorizon: Boolean = false,
        horizonProfile: com.dazix.caelis.domain.horizon.HorizonProfile? = null,
        alignAzimuthOffsetDeg: Float = 0f,
        alignElevationOffsetDeg: Float = 0f,
    ): Int {
        if (totalDsos <= 0 || minTouchRadiusPx <= 0.0f || maxCandidates <= 0) return 0

        val limit = minOf(
            totalDsos,
            dsoScreenX.size,
            dsoScreenY.size,
            dsoRadiusPx.size,
            dsoInFront.size,
        )
        var candidateCount = 0

        val checkOcclusion = (isLandscapeVisible || (useRealHorizon && horizonProfile != null && horizonProfile.isUsable)) &&
            dsoWorldX != null && dsoWorldY != null && dsoWorldZ != null

        for (i in 0 until limit) {
            if (!dsoInFront[i]) continue

            if (checkOcclusion && isOccludedByLandscape(
                    dsoWorldX[i],
                    dsoWorldY[i],
                    dsoWorldZ[i],
                    isLandscapeVisible,
                    useRealHorizon,
                    horizonProfile,
                    alignAzimuthOffsetDeg,
                    alignElevationOffsetDeg,
                )
            ) {
                continue
            }

            val px = dsoScreenX[i]
            val py = dsoScreenY[i]
            val hitRadius = maxOf(minTouchRadiusPx, dsoRadiusPx[i] * 1.25f, 40f)
            val dx = tapX - px
            val dy = tapY - py
            val distanceSq = dx * dx + dy * dy

            if (distanceSq <= hitRadius * hitRadius) {
                val mag = dsoMagnitudes?.getOrNull(i) ?: 6.0f
                val score = computeHitScore(distanceSq, mag)
                candidateCount = insertCandidate(
                    candidateIndex = i,
                    score = score,
                    outIndices = outIndices,
                    outScores = outScores,
                    currentCount = candidateCount,
                    maxCapacity = maxCandidates,
                )
            }
        }

        return candidateCount
    }

    /**
     * Collects top candidate visible Meteor Shower Radiants intersecting the tap area sorted by score.
     * Zero heap allocations.
     */
    fun findTopMeteorRadiants(
        tapX: Float,
        tapY: Float,
        meteorScreenX: FloatArray,
        meteorScreenY: FloatArray,
        meteorInFront: BooleanArray,
        totalMeteors: Int,
        minTouchRadiusPx: Float,
        outIndices: IntArray,
        outScores: FloatArray,
        maxCandidates: Int = minOf(outIndices.size, outScores.size),
        meteorWorldX: FloatArray? = null,
        meteorWorldY: FloatArray? = null,
        meteorWorldZ: FloatArray? = null,
        isLandscapeVisible: Boolean = false,
        useRealHorizon: Boolean = false,
        horizonProfile: com.dazix.caelis.domain.horizon.HorizonProfile? = null,
        alignAzimuthOffsetDeg: Float = 0f,
        alignElevationOffsetDeg: Float = 0f,
    ): Int {
        if (totalMeteors <= 0 || minTouchRadiusPx <= 0.0f || maxCandidates <= 0) return 0

        val limit = minOf(
            totalMeteors,
            meteorScreenX.size,
            meteorScreenY.size,
            meteorInFront.size,
        )
        var candidateCount = 0

        val checkOcclusion = (isLandscapeVisible || (useRealHorizon && horizonProfile != null && horizonProfile.isUsable)) &&
            meteorWorldX != null && meteorWorldY != null && meteorWorldZ != null

        val hitRadius = maxOf(minTouchRadiusPx, 48f)
        val hitRadiusSq = hitRadius * hitRadius

        for (i in 0 until limit) {
            if (!meteorInFront[i]) continue

            if (checkOcclusion && isOccludedByLandscape(
                    meteorWorldX[i],
                    meteorWorldY[i],
                    meteorWorldZ[i],
                    isLandscapeVisible,
                    useRealHorizon,
                    horizonProfile,
                    alignAzimuthOffsetDeg,
                    alignElevationOffsetDeg,
                )
            ) {
                continue
            }

            val px = meteorScreenX[i]
            val py = meteorScreenY[i]
            val dx = tapX - px
            val dy = tapY - py
            val distanceSq = dx * dx + dy * dy

            if (distanceSq <= hitRadiusSq) {
                val score = computeHitScore(distanceSq, 2.0f)
                candidateCount = insertCandidate(
                    candidateIndex = i,
                    score = score,
                    outIndices = outIndices,
                    outScores = outScores,
                    currentCount = candidateCount,
                    maxCapacity = maxCandidates,
                )
            }
        }

        return candidateCount
    }

    /**
     * Collects all candidate Planets and Moon intersecting the tap area sorted by score.
     * Zero heap allocations.
     */
    fun findTopPlanets(
        tapX: Float,
        tapY: Float,
        planetScreenX: FloatArray,
        planetScreenY: FloatArray,
        planetRadiusPx: FloatArray,
        planetInFront: BooleanArray,
        totalPlanets: Int,
        planets: List<PlanetState>,
        minTouchRadiusPx: Float,
        outIndices: IntArray,
        outScores: FloatArray,
        maxCandidates: Int = minOf(outIndices.size, outScores.size),
        planetWorldX: FloatArray? = null,
        planetWorldY: FloatArray? = null,
        planetWorldZ: FloatArray? = null,
        isLandscapeVisible: Boolean = false,
        useRealHorizon: Boolean = false,
        horizonProfile: com.dazix.caelis.domain.horizon.HorizonProfile? = null,
        alignAzimuthOffsetDeg: Float = 0f,
        alignElevationOffsetDeg: Float = 0f,
    ): Int {
        if (totalPlanets <= 0 || minTouchRadiusPx <= 0.0f || planets.isEmpty() || maxCandidates <= 0) return 0

        val limit = minOf(
            totalPlanets,
            planetScreenX.size,
            planetScreenY.size,
            planetRadiusPx.size,
            planetInFront.size,
            planets.size,
        )
        var candidateCount = 0

        val checkOcclusion = (isLandscapeVisible || (useRealHorizon && horizonProfile != null && horizonProfile.isUsable)) &&
            planetWorldX != null && planetWorldY != null && planetWorldZ != null

        for (i in 0 until limit) {
            if (!planetInFront[i]) continue

            if (checkOcclusion && isOccludedByLandscape(
                    planetWorldX[i],
                    planetWorldY[i],
                    planetWorldZ[i],
                    isLandscapeVisible,
                    useRealHorizon,
                    horizonProfile,
                    alignAzimuthOffsetDeg,
                    alignElevationOffsetDeg,
                )
            ) {
                continue
            }

            val px = planetScreenX[i]
            val py = planetScreenY[i]
            val hitRadius = maxOf(120f, minTouchRadiusPx, planetRadiusPx[i])
            val dx = tapX - px
            val dy = tapY - py
            val distanceSq = dx * dx + dy * dy

            if (distanceSq <= hitRadius * hitRadius) {
                val score = computeHitScore(distanceSq, planets[i].magnitude)
                candidateCount = insertCandidate(
                    candidateIndex = i,
                    score = score,
                    outIndices = outIndices,
                    outScores = outScores,
                    currentCount = candidateCount,
                    maxCapacity = maxCandidates,
                )
            }
        }

        return candidateCount
    }

    /**
     * Finds the index of the best visible star to the user's tap coordinates within a maximum touch radius.
     * If [starMagnitudes] is provided, scores stars based on weighted distance and brightness.
     * If [starMagnitudes] is null, falls back to pure distance comparison.
     *
     * @param tapX Touch X coordinate in viewport pixels.
     * @param tapY Touch Y coordinate in viewport pixels.
     * @param starScreenX Pre-projected screen X coordinates array.
     * @param starScreenY Pre-projected screen Y coordinates array.
     * @param starInFront Boolean array tracking whether each star is in front of the camera plane (zCamera > 0).
     * @param totalStars Total count of stars in the catalog to search.
     * @param maxRadiusPx Maximum touch hit radius in pixels (e.g. 48dp converted to px).
     * @param starMagnitudes Optional star magnitudes array for weighted scoring.
     * @return Star index [0, totalStars - 1] of the best star within radius, or -1 if no star was hit.
     */
    fun findClosestVisibleStar(
        tapX: Float,
        tapY: Float,
        starScreenX: FloatArray,
        starScreenY: FloatArray,
        starInFront: BooleanArray,
        totalStars: Int,
        maxRadiusPx: Float,
        starMagnitudes: FloatArray? = null,
    ): Int {
        if (totalStars <= 0 || maxRadiusPx <= 0.0f) return -1

        val maxRadiusSq = maxRadiusPx * maxRadiusPx
        val limit = minOf(totalStars, starScreenX.size, starScreenY.size, starInFront.size)
        var bestIndex = -1
        var bestScore = Float.MAX_VALUE

        for (i in 0 until limit) {
            if (!starInFront[i]) continue

            val dx = tapX - starScreenX[i]
            val dy = tapY - starScreenY[i]
            val distSq = dx * dx + dy * dy

            if (distSq <= maxRadiusSq) {
                val score = if (starMagnitudes != null && i < starMagnitudes.size) {
                    computeHitScore(distSq, starMagnitudes[i])
                } else {
                    distSq
                }

                if (score < bestScore) {
                    bestScore = score
                    bestIndex = i
                }
            }
        }

        return bestIndex
    }
}
