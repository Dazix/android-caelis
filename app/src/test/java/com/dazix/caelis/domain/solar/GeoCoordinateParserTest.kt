package com.dazix.caelis.domain.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoCoordinateParserTest {

    @Test
    fun `empty and blank input returns Empty failure`() {
        assertEquals(CoordinateParseResult.Failure.Empty, GeoCoordinateParser.parse(null))
        assertEquals(CoordinateParseResult.Failure.Empty, GeoCoordinateParser.parse(""))
        assertEquals(CoordinateParseResult.Failure.Empty, GeoCoordinateParser.parse("   "))
    }

    @Test
    fun `decimal degrees signed notation parses correctly`() {
        val r1 = GeoCoordinateParser.parse("50.0755, 14.4378")
        assertTrue(r1 is CoordinateParseResult.Success)
        val s1 = r1 as CoordinateParseResult.Success
        assertEquals(CoordinateFormat.DECIMAL_DEGREES, s1.format)
        assertEquals(50.0755, s1.location.latitude, 0.0001)
        assertEquals(14.4378, s1.location.longitude, 0.0001)

        val r2 = GeoCoordinateParser.parse("-22.9068, -43.1729")
        assertTrue(r2 is CoordinateParseResult.Success)
        val s2 = r2 as CoordinateParseResult.Success
        assertEquals(-22.9068, s2.location.latitude, 0.0001)
        assertEquals(-43.1729, s2.location.longitude, 0.0001)
    }

    @Test
    fun `decimal degrees with comma decimal separator parses correctly`() {
        val r1 = GeoCoordinateParser.parse("50,0755, 14,4378")
        assertTrue(r1 is CoordinateParseResult.Success)
        val s1 = r1 as CoordinateParseResult.Success
        assertEquals(50.0755, s1.location.latitude, 0.0001)
        assertEquals(14.4378, s1.location.longitude, 0.0001)

        val r2 = GeoCoordinateParser.parse("50,0755; 14,4378")
        assertTrue(r2 is CoordinateParseResult.Success)
        val s2 = r2 as CoordinateParseResult.Success
        assertEquals(50.0755, s2.location.latitude, 0.0001)
        assertEquals(14.4378, s2.location.longitude, 0.0001)
    }

    @Test
    fun `decimal degrees with hemisphere letters parses correctly`() {
        val r1 = GeoCoordinateParser.parse("50.0755° N, 14.4378° E")
        assertTrue(r1 is CoordinateParseResult.Success)
        val s1 = r1 as CoordinateParseResult.Success
        assertEquals(50.0755, s1.location.latitude, 0.0001)
        assertEquals(14.4378, s1.location.longitude, 0.0001)

        val r2 = GeoCoordinateParser.parse("22.9068 S, 43.1729 W")
        assertTrue(r2 is CoordinateParseResult.Success)
        val s2 = r2 as CoordinateParseResult.Success
        assertEquals(-22.9068, s2.location.latitude, 0.0001)
        assertEquals(-43.1729, s2.location.longitude, 0.0001)

        val r3 = GeoCoordinateParser.parse("N 50.0755, E 14.4378")
        assertTrue(r3 is CoordinateParseResult.Success)
        val s3 = r3 as CoordinateParseResult.Success
        assertEquals(50.0755, s3.location.latitude, 0.0001)
        assertEquals(14.4378, s3.location.longitude, 0.0001)
    }

    @Test
    fun `DMS format parses correctly`() {
        val r1 = GeoCoordinateParser.parse("""50° 4' 31.8" N, 14° 26' 16.1" E""")
        assertTrue(r1 is CoordinateParseResult.Success)
        val s1 = r1 as CoordinateParseResult.Success
        assertEquals(CoordinateFormat.DEGREES_MINUTES_SECONDS, s1.format)
        assertEquals(50.0755, s1.location.latitude, 0.001)
        assertEquals(14.4378, s1.location.longitude, 0.001)

        // Compact notation without spaces
        val r2 = GeoCoordinateParser.parse("""50°04'31.8"N 14°26'16.1"E""")
        assertTrue(r2 is CoordinateParseResult.Success)
        val s2 = r2 as CoordinateParseResult.Success
        assertEquals(50.0755, s2.location.latitude, 0.001)
        assertEquals(14.4378, s2.location.longitude, 0.001)

        // Prefix hemisphere
        val r3 = GeoCoordinateParser.parse("""N 50° 4' 32", E 14° 26' 16"""")
        assertTrue(r3 is CoordinateParseResult.Success)
        val s3 = r3 as CoordinateParseResult.Success
        assertEquals(50.0755, s3.location.latitude, 0.001)
        assertEquals(14.4378, s3.location.longitude, 0.001)

        // Southern / Western hemispheres
        val r4 = GeoCoordinateParser.parse("""22° 54' 24.5" S, 43° 10' 22.4" W""")
        assertTrue(r4 is CoordinateParseResult.Success)
        val s4 = r4 as CoordinateParseResult.Success
        assertEquals(-22.9068, s4.location.latitude, 0.001)
        assertEquals(-43.1729, s4.location.longitude, 0.001)
    }

    @Test
    fun `DDM format parses correctly`() {
        val r1 = GeoCoordinateParser.parse("50° 04.530' N, 14° 26.268' E")
        assertTrue(r1 is CoordinateParseResult.Success)
        val s1 = r1 as CoordinateParseResult.Success
        assertEquals(CoordinateFormat.DEGREES_DECIMAL_MINUTES, s1.format)
        assertEquals(50.0755, s1.location.latitude, 0.001)
        assertEquals(14.4378, s1.location.longitude, 0.001)

        val r2 = GeoCoordinateParser.parse("N 50° 04.530', E 014° 26.268'")
        assertTrue(r2 is CoordinateParseResult.Success)
        val s2 = r2 as CoordinateParseResult.Success
        assertEquals(50.0755, s2.location.latitude, 0.001)
        assertEquals(14.4378, s2.location.longitude, 0.001)
    }

    @Test
    fun `map URLs and URIs parse correctly`() {
        val googleAt = GeoCoordinateParser.parse("https://www.google.com/maps/@50.0755381,14.4378005,17z")
        assertTrue(googleAt is CoordinateParseResult.Success)
        assertEquals(50.0755, (googleAt as CoordinateParseResult.Success).location.latitude, 0.001)
        assertEquals(14.4378, googleAt.location.longitude, 0.001)
        assertEquals(CoordinateFormat.MAP_LINK, googleAt.format)

        val googleQuery = GeoCoordinateParser.parse("https://maps.google.com/?q=69.3500,17.5000")
        assertTrue(googleQuery is CoordinateParseResult.Success)
        assertEquals(69.35, (googleQuery as CoordinateParseResult.Success).location.latitude, 0.001)
        assertEquals(17.50, googleQuery.location.longitude, 0.001)

        val mapyCz = GeoCoordinateParser.parse("https://mapy.cz/zakladni?y=50.0755&x=14.4378")
        assertTrue(mapyCz is CoordinateParseResult.Success)
        assertEquals(50.0755, (mapyCz as CoordinateParseResult.Success).location.latitude, 0.001)
        assertEquals(14.4378, mapyCz.location.longitude, 0.001)

        val geoUri = GeoCoordinateParser.parse("geo:50.0755,14.4378")
        assertTrue(geoUri is CoordinateParseResult.Success)
        assertEquals(50.0755, (geoUri as CoordinateParseResult.Success).location.latitude, 0.001)
        assertEquals(14.4378, geoUri.location.longitude, 0.001)
    }

    @Test
    fun `out of bounds coordinates return OutOfBounds failure`() {
        val r1 = GeoCoordinateParser.parse("95.0, 14.4378")
        assertTrue(r1 is CoordinateParseResult.Failure.OutOfBounds)
        val o1 = r1 as CoordinateParseResult.Failure.OutOfBounds
        assertEquals(95.0, o1.latitude, 0.001)

        val r2 = GeoCoordinateParser.parse("50.0, 195.0")
        assertTrue(r2 is CoordinateParseResult.Failure.OutOfBounds)
        val o2 = r2 as CoordinateParseResult.Failure.OutOfBounds
        assertEquals(195.0, o2.longitude, 0.001)
    }

    @Test
    fun `malformed text returns InvalidFormat failure`() {
        val r1 = GeoCoordinateParser.parse("hello world")
        assertTrue(r1 is CoordinateParseResult.Failure.InvalidFormat)

        val r2 = GeoCoordinateParser.parse("50.0755") // only one coordinate
        assertTrue(r2 is CoordinateParseResult.Failure.InvalidFormat)
    }
}
