package com.dazix.caelis.domain.location

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPlaceCodecTest {

    private val snezka = SavedPlace("a1", "Sněžka", 50.7360, 15.7399, "Europe/Prague", 1_700_000_000_000L)
    private val awkward = SavedPlace("b2", "Tab\there\nnew \\ line", -33.9, -70.2, "America/Santiago", 5L)

    @Test
    fun `places survive a round trip, names with tabs, newlines and backslashes included`() {
        val decoded = SavedPlaceCodec.decodePlaces(SavedPlaceCodec.encodePlaces(listOf(snezka, awkward)))
        assertEquals(listOf(snezka, awkward), decoded)
    }

    @Test
    fun `an empty store is an empty list`() {
        assertEquals(emptyList<SavedPlace>(), SavedPlaceCodec.decodePlaces(""))
        assertEquals(emptyList<SavedPlace>(), SavedPlaceCodec.decodePlaces(SavedPlaceCodec.encodePlaces(emptyList())))
    }

    @Test
    fun `a damaged line is dropped and the rest of the list is kept`() {
        val raw = listOf(
            SavedPlaceCodec.encodePlaces(listOf(snezka)),
            "garbage",
            "c3\tBad lat\tnot-a-number\t15.0\tUTC\t0",
            "d4\tOut of range\t95.0\t15.0\tUTC\t0",
            "\tNo id\t50.0\t15.0\tUTC\t0",
            SavedPlaceCodec.encodePlaces(listOf(awkward)),
        ).joinToString("\n")
        assertEquals(listOf(snezka, awkward), SavedPlaceCodec.decodePlaces(raw))
    }

    @Test
    fun `a duplicated id keeps the first record`() {
        val raw = SavedPlaceCodec.encodePlaces(listOf(snezka, snezka.copy(name = "Other")))
        assertEquals(listOf(snezka), SavedPlaceCodec.decodePlaces(raw))
    }

    @Test
    fun `an unknown zone id is kept as written and reads as UTC`() {
        val future = snezka.copy(zoneId = "Mars/Olympus_Mons")
        val decoded = SavedPlaceCodec.decodePlaces(SavedPlaceCodec.encodePlaces(listOf(future))).single()
        assertEquals("Mars/Olympus_Mons", decoded.zoneId)
        assertEquals(ZoneOffset.UTC, decoded.zone())
    }

    @Test
    fun `the selection survives a round trip, empty slots as GPS`() {
        val selection = PlaceSelection(sync = true, shared = "a1", sunAr = null, nightSky = "b\t2")
        assertEquals(selection, SavedPlaceCodec.decodeSelection(SavedPlaceCodec.encodeSelection(selection)))
        assertEquals(PlaceSelection(), SavedPlaceCodec.decodeSelection(SavedPlaceCodec.encodeSelection(PlaceSelection())))
    }

    @Test
    fun `an unreadable selection is all GPS, not an exception`() {
        assertEquals(PlaceSelection(), SavedPlaceCodec.decodeSelection(""))
        assertEquals(PlaceSelection(), SavedPlaceCodec.decodeSelection("1\tonly-two"))
        assertTrue(!SavedPlaceCodec.decodeSelection("0\t\t\t").sync)
    }
}
