package com.dazix.caelis.domain.nightsky

import com.dazix.caelis.data.nightsky.Constellation
import com.dazix.caelis.data.nightsky.DeepSkyObject
import com.dazix.caelis.data.nightsky.MeteorShower
import com.dazix.caelis.data.nightsky.StarMetadata
import java.io.FileInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyNamesTest {

    @Test
    fun `parse reads key-tab-name lines and skips comments, blanks and broken lines`() {
        val names = SkyNames.parse(
            "# header\n\ncon.UMa\tVelká medvědice\r\nno tab here\n\tno key\ndso.M31\t  \nmet.6\tPerseidy\n"
        )
        assertEquals(2, names.size)
        assertEquals("Velká medvědice", names["con.UMa"])
        assertEquals("Perseidy", names["met.6"])
        assertNull("an empty name is missing, not blank", names["dso.M31"])
    }

    @Test
    fun `lookups fall back to the English the caller passes, never to nothing`() {
        val names = SkyNames(
            mapOf("con.UMa" to "Großer Bär", "ast.BDr" to "Großer Wagen", "dso.M31" to "Andromedagalaxie", "type.red_giant" to "Roter Riese")
        )
        assertEquals("Großer Bär", names.constellation("UMa", isAsterism = false, fallback = "Ursa Major"))
        assertEquals("an asterism code is looked up under ast.", "Big Dipper", names.constellation("UMa", isAsterism = true, fallback = "Big Dipper"))
        assertEquals("Großer Wagen", names.constellation("BDr", isAsterism = true, fallback = "Big Dipper"))
        assertEquals("Orion", names.constellation(null, isAsterism = false, fallback = "Orion"))
        assertEquals("Andromedagalaxie", names.messier(31, "Andromeda Galaxy"))
        assertEquals("Crab Nebula", names.messier(1, "Crab Nebula"))
        assertEquals("Mars", names.planet("Mars", "Mars"))
        assertEquals("Großer Bär", names.constellationOfCode("UMa"))
        assertEquals("an unknown code stays readable", "Ori", names.constellationOfCode("Ori"))
        assertEquals("Roter Riese", names.starType("Red Giant"))
        assertEquals("Blue Giant", names.starType("Blue Giant"))
        assertNull(names.starType(null))
    }

    @Test
    fun `typeKey matches the keys the generator wrote`() {
        assertEquals("type.red_giant", SkyNames.typeKey("Red Giant"))
        assertEquals("type.orange_red_star", SkyNames.typeKey("Orange/Red Star"))
    }

    /** Every id the shipped catalog uses has an English name, so no label falls back to a code. */
    @Test
    fun `the default names cover every id in the shipped catalog`() {
        val catalog = FileInputStream(resDir().resolve("raw/catalog.bin")).use { NightSkyCatalogLoader.loadFromStream(it) }
        val names = skyNames(null)
        val missing = mutableListOf<String>()

        val c = Constellation()
        for (i in 0 until catalog.constellationsLength()) {
            catalog.constellations(c, i)
            val key = (if (c.isAsterism()) "ast." else "con.") + c.id()
            if (names[key] == null) missing += key
        }
        val d = DeepSkyObject()
        for (i in 0 until catalog.dsosLength()) {
            catalog.dsos(d, i)
            if (names["dso.M${d.messierId()}"] == null) missing += "dso.M${d.messierId()}"
        }
        val m = MeteorShower()
        for (i in 0 until catalog.meteorShowersLength()) {
            catalog.meteorShowers(m, i)
            if (names["met.${m.id()}"] == null) missing += "met.${m.id()}"
            m.constellationId()?.let { if (names["con.$it"] == null) missing += "con.$it (radiant)" }
        }
        val s = StarMetadata()
        for (i in 0 until catalog.metadataLength()) {
            catalog.metadata(s, i)
            s.bodyType()?.let { if (names[SkyNames.typeKey(it)] == null) missing += SkyNames.typeKey(it) }
            s.constellation()?.takeIf { it.isNotEmpty() }?.let { if (names["con.$it"] == null) missing += "con.$it (star)" }
        }
        assertTrue("names missing for ${missing.distinct()}", missing.isEmpty())
    }
}
