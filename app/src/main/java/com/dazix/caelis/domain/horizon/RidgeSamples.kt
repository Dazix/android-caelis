package com.dazix.caelis.domain.horizon

/**
 * The visible ridges along every azimuth of a [HorizonProfile], as the ray march met them.
 *
 * A ridge point is terrain that rose above everything nearer on its ray and behind which the land
 * fell away out of sight before anything further overtopped it: the silhouette of a nearer ridge
 * standing in front of further ones. The points of azimuth `i` are `offsets[i] until offsets[i + 1]`,
 * nearest first. When terrain set the skyline of an azimuth, its last point is that skyline point and
 * carries the same altitude as [HorizonProfile.elevationsDegrees]; an azimuth whose skyline is the
 * bare geometric dip (open sea) has no points at all.
 *
 * Kept raw on purpose: which points form one line, where the gaps go and which lines are too short
 * to draw are decided when the scene builds its geometry ([RidgeLinker]), so those thresholds can be
 * tuned without recomputing anyone's stored profile.
 */
class RidgeSamples(
    val offsets: IntArray,
    val distancesMeters: FloatArray,
    val altitudesDegrees: FloatArray,
) {
    init {
        require(offsets.size == HorizonProfile.SAMPLE_COUNT + 1) {
            "expected ${HorizonProfile.SAMPLE_COUNT + 1} offsets, got ${offsets.size}"
        }
        require(offsets[0] == 0) { "offsets must start at 0" }
        for (i in 0 until HorizonProfile.SAMPLE_COUNT) {
            val count = offsets[i + 1] - offsets[i]
            require(count in 0..MAX_INNER_RIDGES_PER_AZIMUTH + 1) { "azimuth $i has $count points" }
        }
        require(distancesMeters.size == offsets.last() && altitudesDegrees.size == offsets.last()) {
            "arrays do not match the offsets"
        }
    }

    fun count(azimuthIndex: Int): Int = offsets[azimuthIndex + 1] - offsets[azimuthIndex]

    companion object {
        /**
         * Inner ridges kept per azimuth, besides the skyline point. A screen shows a handful of
         * layers; more is hatching, which the drawing must never become. Bounds the stored profile
         * (at most 1440 x 6 points) and the scene's line batch alike.
         */
        const val MAX_INNER_RIDGES_PER_AZIMUTH = 5
    }
}
