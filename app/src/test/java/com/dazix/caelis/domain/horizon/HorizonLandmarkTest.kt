package com.dazix.caelis.domain.horizon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizonLandmarkTest {

    private fun landmark(name: String, vararg names: Pair<String, String>) = HorizonLandmark(
        name = name,
        names = mapOf(*names),
        elevationMeters = 1603,
        distanceMeters = 10_000.0,
        azimuthDegrees = 12.0,
        apparentElevationDegrees = 4.5,
    )

    private val sniezka = landmark(
        "Śnieżka",
        "cs" to "Sněžka",
        "de" to "Schneekoppe",
        "en" to "Sniezka",
    )

    @Test
    fun `the name in the app language comes first, whatever the tag looks like`() {
        assertEquals("Sněžka", sniezka.displayName("cs"))
        assertEquals("Sněžka", sniezka.displayName("cs-CZ"))
        assertEquals("Sněžka", sniezka.displayName("CS_cz"))
        assertEquals("Schneekoppe", sniezka.displayName("de"))
        assertEquals("Schneekoppe", sniezka.displayName("de-AT"))
        assertEquals("Sniezka", sniezka.displayName("en"))
        assertEquals("Sniezka", sniezka.displayName("en-GB"))
    }

    @Test
    fun `czech and slovak stand in for each other`() {
        assertEquals("Sněžka", sniezka.displayName("sk"))
        val gerlach = landmark("Gerlachovský štít", "pl" to "Gerlach", "en" to "Gerlach Peak", "cs" to "Gerlachovský štít")

        assertEquals("Gerlachovský štít", gerlach.displayName("cs"))
        val onlySlovak = landmark("Kriváň", "sk" to "Kriváň", "en" to "Krivan")
        assertEquals("Kriváň", onlySlovak.displayName("cs"))
        val onlyCzech = landmark("Śnieżka", "cs" to "Sněžka", "en" to "Sniezka")
        assertEquals("Sněžka", onlyCzech.displayName("sk"))
    }

    @Test
    fun `a latin local name beats english for every language but english`() {
        val peak = landmark("Śnieżka", "en" to "Sniezka")

        assertEquals("Śnieżka", peak.displayName("pl"))
        assertEquals("Śnieżka", peak.displayName("de"))
        assertEquals("Śnieżka", peak.displayName("cs"))
        assertEquals("Śnieżka", peak.displayName("fr"))
        assertEquals("Śnieżka", peak.displayName(""))
        assertEquals("Sniezka", peak.displayName("en"))
    }

    @Test
    fun `a local name in another script gives way to english, then to the latin spelling`() {
        val fuji = landmark("富士山", "en" to "Mount Fuji", LandmarkNames.LATIN to "Fuji")
        val olympus = landmark("Όλυμπος", LandmarkNames.LATIN to "Olympos")

        for (language in listOf("cs", "de", "en", "pl", "sk")) {
            assertEquals(language, "Mount Fuji", fuji.displayName(language))
            assertEquals(language, "Olympos", olympus.displayName(language))
        }
    }

    @Test
    fun `with nothing readable the local name is printed rather than nothing`() {
        val peak = landmark("玉山")

        assertEquals("玉山", peak.displayName("cs"))
        assertEquals("玉山", peak.displayName("en"))
    }

    @Test
    fun `english without an english name takes the latin spelling, then the local name`() {
        assertEquals("Elbrus", landmark("Эльбрус", LandmarkNames.LATIN to "Elbrus").displayName("en"))
        assertEquals("Śnieżka", landmark("Śnieżka", "cs" to "Sněžka").displayName("en"))
    }

    @Test
    fun `a blank entry counts as missing`() {
        val peak = landmark("Śnieżka", "cs" to "  ", "sk" to "", "en" to " ")

        assertEquals("Śnieżka", peak.displayName("cs"))
        assertEquals("Śnieżka", peak.displayName("en"))
    }

    @Test
    fun `latin script is told apart by its letters only`() {
        for (text in listOf("Sněžka", "Śnieżka", "Gerlachovský štít", "K2", "Mont Blanc 4808", "Puʻukaʻala")) {
            assertTrue(text, LandmarkNames.isLatin(text))
        }
        for (text in listOf("富士山", "Όλυμπος", "Эльбрус", "Mount 富士", "", "1603", "🏔")) {
            assertFalse(text, LandmarkNames.isLatin(text))
        }
    }

    @Test
    fun `the label is the name for the language and the height in metres`() {
        assertEquals("Sněžka · 1603 m", sniezka.label("cs"))
        assertEquals("Sniezka · 1603 m", sniezka.label("en"))
        assertEquals("Schneekoppe · 1603 m", sniezka.label("de"))
        assertEquals("Śnieżka · 1603 m", landmark("Śnieżka").label("en"))
    }
}
