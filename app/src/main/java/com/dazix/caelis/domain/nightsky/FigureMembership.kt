package com.dazix.caelis.domain.nightsky

/**
 * Which constellation and asterism figures a star belongs to, and which constellations an asterism
 * is drawn across, answered from the figures' own line stars.
 *
 * Membership is the figure as drawn: a star is in an asterism when one of the asterism's lines ends
 * on it. That is what the user sees highlighted after following the link, so the detail sheet never
 * names a figure the selected star is not part of. A star's IAU constellation comes from the
 * catalog's code first ([constellationForStar]), because a named star outside every stick figure
 * still has one; the lines are only the fallback.
 *
 * An asterism's parent constellations are the ones sharing at least one line star with it, most
 * shared stars first. The catalog's own `family` text for an asterism is English free text ("Orion
 * / Taurus / Gemini", "Winter Hexagon") that can be neither translated nor selected.
 *
 * Built once per catalog; the lookups allocate only their result.
 */
class FigureMembership(figures: List<Figure>) {

    /**
     * One entry of the catalog's constellation table.
     *
     * @param index Position in the catalog, what `selectConstellationByIndex` takes.
     * @param code IAU code of a constellation ("Ori") or Stellarium code of an asterism.
     * @param stars Star indices of the figure's lines, in pairs, repetitions allowed.
     */
    class Figure(val index: Int, val code: String?, val isAsterism: Boolean, val stars: IntArray)

    private val constellationByCode = HashMap<String, Int>()
    private val figuresByStar = HashMap<Int, IntArray>()
    private val starsByFigure = HashMap<Int, IntArray>()
    private val asterismFlag = HashMap<Int, Boolean>()
    private val order = ArrayList<Int>(figures.size)

    init {
        val byStar = HashMap<Int, MutableList<Int>>()
        for (figure in figures) {
            order += figure.index
            asterismFlag[figure.index] = figure.isAsterism
            val distinct = figure.stars.distinct().toIntArray()
            starsByFigure[figure.index] = distinct
            if (!figure.isAsterism && !figure.code.isNullOrEmpty()) {
                constellationByCode.putIfAbsent(figure.code, figure.index)
            }
            for (star in distinct) byStar.getOrPut(star) { ArrayList(2) } += figure.index
        }
        for ((star, list) in byStar) figuresByStar[star] = list.toIntArray()
    }

    /**
     * The constellation figure of a star: the one named by the catalog's [iauCode] when the
     * catalog has such a figure, otherwise the first constellation whose lines use the star, or null.
     */
    fun constellationForStar(starIndex: Int, iauCode: String?): Int? {
        if (!iauCode.isNullOrEmpty()) constellationByCode[iauCode]?.let { return it }
        return figuresByStar[starIndex]?.firstOrNull { asterismFlag[it] == false }
    }

    /** Every asterism whose lines use the star, in catalog order. */
    fun asterismsOfStar(starIndex: Int): List<Int> =
        figuresByStar[starIndex]?.filter { asterismFlag[it] == true }.orEmpty()

    /** The constellation figure with this IAU code ("Ori"), or null. */
    fun constellationOfCode(iauCode: String?): Int? =
        if (iauCode.isNullOrEmpty()) null else constellationByCode[iauCode]

    /**
     * The constellations an asterism shares line stars with, most shared stars first, ties in
     * catalog order. Empty for an index that is not an asterism.
     */
    fun parentsOfAsterism(asterismIndex: Int): List<Int> {
        if (asterismFlag[asterismIndex] != true) return emptyList()
        val stars = starsByFigure[asterismIndex] ?: return emptyList()
        val shared = HashMap<Int, Int>()
        for (star in stars) {
            for (figure in figuresByStar[star] ?: continue) {
                if (asterismFlag[figure] == false) shared[figure] = (shared[figure] ?: 0) + 1
            }
        }
        return order.filter { it in shared }.sortedByDescending { shared.getValue(it) }
    }
}
