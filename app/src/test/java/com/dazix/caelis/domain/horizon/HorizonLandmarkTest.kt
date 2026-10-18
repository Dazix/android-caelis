package com.dazix.caelis.domain.horizon

import org.junit.Assert.assertEquals
import org.junit.Test

class HorizonLandmarkTest {

    private fun landmark(name: String, nameCs: String = "", nameEn: String = "") = HorizonLandmark(
        name = name,
        nameCs = nameCs,
        nameEn = nameEn,
        elevationMeters = 1603,
        distanceMeters = 10_000.0,
        azimuthDegrees = 12.0,
        apparentElevationDegrees = 4.5,
    )

    @Test
    fun `czech prefers the czech name`() {
        val peak = landmark("Śnieżka", nameCs = "Sněžka", nameEn = "Sniezka")

        assertEquals("Sněžka", peak.displayName("cs"))
        assertEquals("Sněžka", peak.displayName("cs-CZ"))
        assertEquals("Sněžka", peak.displayName("CS_cz"))
    }

    @Test
    fun `czech falls back to the local name, not to english`() {
        val peak = landmark("Śnieżka", nameEn = "Sniezka")

        assertEquals("Śnieżka", peak.displayName("cs"))
    }

    @Test
    fun `english and every other language prefer the english name`() {
        val peak = landmark("Śnieżka", nameCs = "Sněžka", nameEn = "Sniezka")

        assertEquals("Sniezka", peak.displayName("en"))
        assertEquals("Sniezka", peak.displayName("en-GB"))
        assertEquals("Sniezka", peak.displayName("de"))
        assertEquals("Sniezka", peak.displayName(""))
    }

    @Test
    fun `other languages fall back to the local name, not to czech`() {
        val peak = landmark("Śnieżka", nameCs = "Sněžka")

        assertEquals("Śnieżka", peak.displayName("en"))
    }

    @Test
    fun `a blank translation counts as missing`() {
        val peak = landmark("Śnieżka", nameCs = "  ", nameEn = "")

        assertEquals("Śnieżka", peak.displayName("cs"))
        assertEquals("Śnieżka", peak.displayName("en"))
    }

    @Test
    fun `the label is the name for the language and the height in metres`() {
        val peak = landmark("Śnieżka", nameCs = "Sněžka", nameEn = "Sniezka")

        assertEquals("Sněžka · 1603 m", peak.label("cs"))
        assertEquals("Sniezka · 1603 m", peak.label("en"))
        assertEquals("Śnieżka · 1603 m", landmark("Śnieżka").label("en"))
    }
}
