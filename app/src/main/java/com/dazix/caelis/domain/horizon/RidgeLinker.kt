package com.dazix.caelis.domain.horizon

import kotlin.math.abs
import kotlin.math.min

/**
 * One ridge as the eye sees it: consecutive azimuth samples from [startIndex] (wrapping past north),
 * one apparent altitude per sample. [skyline] tells, per point, whether that point was its azimuth's
 * skyline; a ridge can stand on the skyline for a stretch and then run on in front of a further one.
 * A [closed] line went all the way round and joins its own start.
 */
class RidgeLine(
    val startIndex: Int,
    val altitudesDegrees: FloatArray,
    val skyline: BooleanArray,
    val closed: Boolean,
) {
    val size: Int get() = altitudesDegrees.size
}

/**
 * Turns the ridge points of a profile into the lines Sun AR draws.
 *
 * A point continues the line of a point on the neighbouring azimuth when the two lie at about the same
 * distance and altitude; that is what one ridge looks like from one place. Where a further ridge goes
 * behind a nearer one that runs on in front of it, its line stops [GAP_SAMPLES] short, so the two never
 * touch: the eye reads the nearer one as standing in front. The nearer line is never shortened, and
 * the skyline is never left with a hole. A ridge that just flattens out ends where it ends.
 *
 * Runs once per profile when the scene builds its geometry, never per frame.
 */
object RidgeLinker {

    /** Same ridge on the next azimuth: distances within 12 %, i.e. 600 m at 5 km. */
    const val LINK_RELATIVE_DISTANCE = 0.12f

    /**
     * The same between two skyline points. On a broad, rounded summit the highest sample wanders
     * 20-30 % in distance from ray to ray while the eye sees one unbroken crest; at 12 % the skyline
     * fell apart into dashes there. Where the skyline really passes to a ridge behind, the jump is
     * far larger (3 km to 25 km).
     */
    const val LINK_RELATIVE_DISTANCE_SKYLINE = 0.35f

    /**
     * A floor under either: near the observer one 30 m step of the ray is already more than 12 %,
     * and a bank 250 m away would otherwise be cut up by the sampling, not by the land.
     */
    const val LINK_MIN_DISTANCE_METERS = 150f

    /** Same ridge on the next azimuth: altitude within 0.6 degrees between two samples 0.25 degrees apart. */
    const val LINK_ALTITUDE_DEGREES = 0.6f

    /**
     * Shorter lines (5 degrees) are a knoll or noise in the elevation model, not a ridge the eye
     * follows. At 2 degrees the view was hatched with short strokes.
     */
    const val MIN_LINE_SAMPLES = 20

    /** The gap a further ridge leaves before the nearer one in front of it: half a degree. */
    const val GAP_SAMPLES = 2

    fun link(samples: RidgeSamples): List<RidgeLine> {
        val n = HorizonProfile.SAMPLE_COUNT
        val offsets = samples.offsets
        val total = offsets[n]
        if (total == 0) return emptyList()
        val distance = samples.distancesMeters
        val altitude = samples.altitudesDegrees

        val azimuthOf = IntArray(total)
        val isSkyline = BooleanArray(total)
        for (i in 0 until n) {
            for (g in offsets[i] until offsets[i + 1]) azimuthOf[g] = i
            if (offsets[i + 1] > offsets[i]) isSkyline[offsets[i + 1] - 1] = true
        }

        // Match each azimuth's points to the next azimuth's, closest relative distance first.
        val next = IntArray(total) { -1 }
        val prev = IntArray(total) { -1 }
        for (i in 0 until n) {
            val j = (i + 1) % n
            while (true) {
                var bestP = -1
                var bestQ = -1
                var bestScore = Float.MAX_VALUE
                for (p in offsets[i] until offsets[i + 1]) {
                    if (next[p] >= 0) continue
                    for (q in offsets[j] until offsets[j + 1]) {
                        if (prev[q] >= 0) continue
                        val gap = abs(distance[p] - distance[q])
                        val nearer = min(distance[p], distance[q])
                        val score = gap / nearer
                        val tolerance = if (isSkyline[p] && isSkyline[q]) LINK_RELATIVE_DISTANCE_SKYLINE else LINK_RELATIVE_DISTANCE
                        if (score > tolerance && gap > LINK_MIN_DISTANCE_METERS) continue
                        if (abs(altitude[p] - altitude[q]) > LINK_ALTITUDE_DEGREES) continue
                        if (score < bestScore) {
                            bestScore = score
                            bestP = p
                            bestQ = q
                        }
                    }
                }
                if (bestP < 0) break
                next[bestP] = bestQ
                prev[bestQ] = bestP
            }
        }

        // Every chain first, then which of them are drawn, and only then the gaps: a line that is
        // left out must not make another give way to it. On the device a 19-sample ridge cut the end
        // off the one behind it and was then dropped itself, which left a hole in the skyline.
        val chains = ArrayList<IntArray>()
        val closedChains = ArrayList<Boolean>()
        val visited = BooleanArray(total)
        val scratch = IntArray(total)

        // Open lines start at a point nothing leads into.
        for (g in 0 until total) {
            if (prev[g] >= 0) continue
            var length = 0
            var p = g
            while (p >= 0) {
                visited[p] = true
                scratch[length++] = p
                p = next[p]
            }
            // A chain that winds on past a full turn (a ridge handed over to another one further
            // round) is cut at one turn: the scene's path buffer holds one turn, and a second lap
            // would draw over the first.
            chains += scratch.copyOf(min(length, n))
            closedChains += false
        }
        // What is left runs in circles. One turn round the sky is a closed ridge; a chain that comes
        // back only after several turns (a spiral the tolerances let through) is cut to one open turn.
        for (g in 0 until total) {
            if (visited[g]) continue
            var length = 0
            var p = g
            while (!visited[p]) {
                visited[p] = true
                scratch[length++] = p
                p = next[p]
            }
            val closed = length == n
            chains += scratch.copyOf(if (closed) length else min(length, n - 1))
            closedChains += closed
        }

        // A stretch of skyline is drawn at any length: a short one between two ridges is still the
        // horizon, and dropping it cut a hole in it on the device.
        fun longEnough(chain: IntArray, from: Int, to: Int): Boolean {
            val onSkyline = (from until to).any { isSkyline[chain[it]] }
            return to - from >= if (onSkyline) 2 else MIN_LINE_SAMPLES
        }

        val drawn = BooleanArray(total)
        for ((c, chain) in chains.withIndex()) {
            if (closedChains[c] || longEnough(chain, 0, chain.size)) for (g in chain) drawn[g] = true
        }

        val lines = ArrayList<RidgeLine>()
        for ((c, chain) in chains.withIndex()) {
            val closed = closedChains[c]
            var from = 0
            var to = chain.size
            if (!closed) {
                if (!longEnough(chain, from, to)) continue
                // Give way to a drawn, nearer line that runs on in front of this end, and only as far
                // as it reaches: where it stops sooner, cutting the full gap left sky with no line.
                val last = chain[to - 1]
                to -= giveWay(samples, last, (azimuthOf[last] + 1) % n, prev, drawn)
                from += giveWay(samples, chain[0], (azimuthOf[chain[0]] + n - 1) % n, next, drawn)
                if (!longEnough(chain, from, to)) continue
            }
            val size = to - from
            lines += RidgeLine(
                startIndex = azimuthOf[chain[from]],
                altitudesDegrees = FloatArray(size) { altitude[chain[from + it]] },
                skyline = BooleanArray(size) { isSkyline[chain[from + it]] },
                closed = closed,
            )
        }
        return bridged(lines)
    }

    /**
     * Two lines that meet end to end, one ending on the azimuth before the other starts, have a
     * quarter degree between them that neither draws; on the device it read as a hole in the
     * horizon. The first reaches over to the second's first point when the two stand within
     * [LINK_ALTITUDE_DEGREES] of each other. A larger step is a ridge behind another and stays open.
     */
    private fun bridged(lines: List<RidgeLine>): List<RidgeLine> {
        val n = HorizonProfile.SAMPLE_COUNT
        val startingAt = HashMap<Int, MutableList<Int>>()
        for ((i, line) in lines.withIndex()) if (!line.closed) startingAt.getOrPut(line.startIndex) { ArrayList() } += i
        val taken = BooleanArray(lines.size)
        return lines.mapIndexed { i, line ->
            if (line.closed || line.size >= n) return@mapIndexed line
            val endAltitude = line.altitudesDegrees[line.size - 1]
            val after = (line.startIndex + line.size) % n
            var best = -1
            var bestStep = LINK_ALTITUDE_DEGREES
            for (j in startingAt[after].orEmpty()) {
                if (j == i || taken[j]) continue
                val step = abs(lines[j].altitudesDegrees[0] - endAltitude)
                if (step <= bestStep) {
                    bestStep = step
                    best = j
                }
            }
            if (best < 0) return@mapIndexed line
            taken[best] = true
            RidgeLine(
                startIndex = line.startIndex,
                altitudesDegrees = line.altitudesDegrees + lines[best].altitudesDegrees[0],
                skyline = line.skyline + lines[best].skyline[0],
                closed = false,
            )
        }
    }

    /**
     * How many samples the end at point [g] gives way, at most [GAP_SAMPLES]: to a drawn crest on the
     * next [azimuth] out that is nearer than [g] and stands at least as high give or take, and whose
     * line runs on back over [g]'s own samples ([link] walks it there). It is cut back as far as that
     * line covers, so the far line stops short of it without leaving a stretch where neither is drawn.
     * A nearer line that starts or stops right beside the end does not cover it, and the two meet end
     * to end.
     */
    private fun giveWay(samples: RidgeSamples, g: Int, azimuth: Int, link: IntArray, drawn: BooleanArray): Int {
        var most = 0
        for (q in samples.offsets[azimuth] until samples.offsets[azimuth + 1]) {
            if (!drawn[q] ||
                samples.distancesMeters[q] >= samples.distancesMeters[g] ||
                samples.altitudesDegrees[q] < samples.altitudesDegrees[g] - LINK_ALTITUDE_DEGREES
            ) continue
            var covered = 0
            var p = link[q]
            while (p >= 0 && drawn[p] && covered < GAP_SAMPLES) {
                covered++
                p = link[p]
            }
            most = maxOf(most, covered)
        }
        return most
    }
}
