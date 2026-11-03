package com.dazix.caelis.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingPlaceParserTest {

    private fun found(result: IncomingPlace): IncomingPlace.Found {
        assertTrue("expected a position, got $result", result is IncomingPlace.Found)
        return result as IncomingPlace.Found
    }

    @Test
    fun `plain geo link`() {
        val r = found(IncomingPlaceParser.parse("geo:50.7360,15.7399"))
        assertEquals(50.7360, r.location.latitude, 1e-6)
        assertEquals(15.7399, r.location.longitude, 1e-6)
        assertNull(r.suggestedName)
    }

    @Test
    fun `geo link with a placeholder path takes the query and its label - not 0,0`() {
        val r = found(IncomingPlaceParser.parse("geo:0,0?q=50.7360,15.7399(Sn%C4%9B%C5%BEka)"))
        assertEquals(50.7360, r.location.latitude, 1e-6)
        assertEquals(15.7399, r.location.longitude, 1e-6)
        assertEquals("Sněžka", r.suggestedName)
    }

    @Test
    fun `shared text with a name line and a full Mapy link`() {
        val text = "Sněžka\nhttps://mapy.com/cs/turisticka?x=15.7399&y=50.7360&z=15"
        val r = found(IncomingPlaceParser.parse(text))
        assertEquals(50.7360, r.location.latitude, 1e-6)
        assertEquals(15.7399, r.location.longitude, 1e-6)
        assertEquals("Sněžka", r.suggestedName)
    }

    @Test
    fun `the subject outranks the first line as a name`() {
        val r = found(IncomingPlaceParser.parse("Look here\n50.7360, 15.7399", subject = "Sněžka"))
        assertEquals("Sněžka", r.suggestedName)
    }

    @Test
    fun `coordinates in prose are read and are not offered as the name`() {
        val r = found(IncomingPlaceParser.parse("50°44'09.6\"N 15°44'23.6\"E"))
        assertEquals(50.7360, r.location.latitude, 1e-3)
        assertEquals(15.7399, r.location.longitude, 1e-3)
        assertNull(r.suggestedName)
    }

    @Test
    fun `Mapy_com share of a named point - name, address, DMS line, short link`() {
        val text = "Rozhledna Ještěd\n" +
            "Liberec XIX-Horní Hanychov 153, Liberec, 460 08, Liberec\n" +
            "50°43'57.745\"N, 14°59'4.485\"E\n" +
            "https://mapy.com/s/kubapacate"
        val r = found(IncomingPlaceParser.parse(text))
        assertEquals(50.0 + 43.0 / 60 + 57.745 / 3600, r.location.latitude, 1e-6)
        assertEquals(14.0 + 59.0 / 60 + 4.485 / 3600, r.location.longitude, 1e-6)
        assertEquals("Rozhledna Ještěd", r.suggestedName)
    }

    @Test
    fun `Mapy_com share of a bare map position`() {
        val text = "Pozice na mapě\n50°44'50.589\"N, 14°57'57.793\"E\nhttps://mapy.com/s/darunuluda"
        val r = found(IncomingPlaceParser.parse(text))
        assertEquals(50.0 + 44.0 / 60 + 50.589 / 3600, r.location.latitude, 1e-6)
        assertEquals(14.0 + 57.0 / 60 + 57.793 / 3600, r.location.longitude, 1e-6)
    }

    @Test
    fun `a short link is recognised and never followed`() {
        val r = IncomingPlaceParser.parse("Sněžka\nhttps://mapy.com/s/gopefabeca")
        assertTrue(r is IncomingPlace.ShortLink)
        assertEquals("Sněžka", r.suggestedName)
        assertTrue(IncomingPlaceParser.parse("https://maps.app.goo.gl/abc123") is IncomingPlace.ShortLink)
    }

    @Test
    fun `text without a position is NotFound`() {
        assertTrue(IncomingPlaceParser.parse("See you at the summit") is IncomingPlace.NotFound)
        assertTrue(IncomingPlaceParser.parse(null) is IncomingPlace.NotFound)
        assertTrue(IncomingPlaceParser.parse("geo:91,15") is IncomingPlace.NotFound)
    }

    @Test
    fun `percent decoding is UTF-8 and keeps a malformed escape as text`() {
        assertEquals("Sněžka 1 603", IncomingPlaceParser.decode("Sn%C4%9B%C5%BEka+1%20603"))
        assertEquals("100%zz", IncomingPlaceParser.decode("100%zz"))
        assertEquals("end%", IncomingPlaceParser.decode("end%"))
        assertEquals("Žďár", IncomingPlaceParser.decode("Žďár"))
    }

    @Test
    fun `a long first line is cut to a name`() {
        val long = "x".repeat(200)
        val r = found(IncomingPlaceParser.parse("$long\ngeo:50.7360,15.7399"))
        assertEquals(IncomingPlaceParser.MAX_NAME_LENGTH + 1, r.suggestedName!!.length)
    }
}
