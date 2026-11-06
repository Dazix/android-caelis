package com.dazix.caelis.domain.nightsky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FigureMembershipTest {

    // Star indices: 1 Betelgeuse, 2 Rigel, 3 Bellatrix, 4 belt star, 5 Sirius, 6 Procyon,
    // 7 Aldebaran, 8 a faint star in no figure, 9 Adhara (CMa only).
    private val orion = FigureMembership.Figure(0, "Ori", false, intArrayOf(1, 3, 3, 4, 4, 2))
    private val canisMajor = FigureMembership.Figure(1, "CMa", false, intArrayOf(5, 9))
    private val canisMinor = FigureMembership.Figure(2, "CMi", false, intArrayOf(6, 6))
    private val taurus = FigureMembership.Figure(3, "Tau", false, intArrayOf(7, 7))
    private val winterTriangle = FigureMembership.Figure(4, "WTr", true, intArrayOf(1, 5, 5, 6, 6, 1))
    private val winterHexagon = FigureMembership.Figure(5, "WHx", true, intArrayOf(2, 5, 5, 6, 6, 7, 7, 2))
    private val belt = FigureMembership.Figure(6, "OrB", true, intArrayOf(4, 4))
    private val lonely = FigureMembership.Figure(7, "Lon", true, intArrayOf(8, 8))

    private val membership = FigureMembership(
        listOf(orion, canisMajor, canisMinor, taurus, winterTriangle, winterHexagon, belt, lonely),
    )

    @Test
    fun `a star lists every asterism whose lines use it and no other`() {
        assertEquals(listOf(4, 5), membership.asterismsOfStar(5))
        assertEquals(listOf(6), membership.asterismsOfStar(4))
        assertEquals(listOf(4), membership.asterismsOfStar(1))
        assertEquals(emptyList<Int>(), membership.asterismsOfStar(3))
        assertEquals(emptyList<Int>(), membership.asterismsOfStar(42))
    }

    @Test
    fun `an asterism is never offered as the constellation of a star`() {
        // Star 8 is only in an asterism: no constellation figure, not the asterism instead.
        assertNull(membership.constellationForStar(8, null))
    }

    @Test
    fun `the catalog's code names the constellation before the lines do`() {
        // Bellatrix is on Orion's lines, but a catalog code wins even where they would disagree.
        assertEquals(1, membership.constellationForStar(3, "CMa"))
        assertEquals(0, membership.constellationForStar(3, "Ori"))
    }

    @Test
    fun `without a code, or with one that has no figure, the lines decide`() {
        assertEquals(0, membership.constellationForStar(2, null))
        assertEquals(1, membership.constellationForStar(9, ""))
        assertEquals(3, membership.constellationForStar(7, "Xyz"))
        assertNull(membership.constellationForStar(42, null))
    }

    @Test
    fun `a code never resolves to an asterism with the same code`() {
        assertNull(membership.constellationOfCode("WTr"))
        assertEquals(2, membership.constellationOfCode("CMi"))
        assertNull(membership.constellationOfCode(null))
    }

    @Test
    fun `an asterism's parents are the constellations it shares stars with, most shared first`() {
        // Winter Hexagon: Rigel (Ori), Sirius (CMa), Procyon (CMi), Aldebaran (Tau), one each.
        assertEquals(listOf(0, 1, 2, 3), membership.parentsOfAsterism(5))
        assertEquals(listOf(0, 1, 2), membership.parentsOfAsterism(4))
        assertEquals(listOf(0), membership.parentsOfAsterism(6))
    }

    @Test
    fun `more shared stars rank a parent first regardless of catalog order`() {
        val m = FigureMembership(
            listOf(
                FigureMembership.Figure(0, "A", false, intArrayOf(1, 2)),
                FigureMembership.Figure(1, "B", false, intArrayOf(3, 4, 4, 5)),
                FigureMembership.Figure(2, "X", true, intArrayOf(1, 3, 3, 4, 4, 5)),
            ),
        )
        assertEquals(listOf(1, 0), m.parentsOfAsterism(2))
    }

    @Test
    fun `an asterism outside every constellation and a constellation have no parents`() {
        assertTrue(membership.parentsOfAsterism(7).isEmpty())
        assertTrue(membership.parentsOfAsterism(0).isEmpty())
        assertTrue(membership.parentsOfAsterism(99).isEmpty())
    }
}
