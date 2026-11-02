package com.dazix.caelis.domain.location

import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.solar.GeoLocation
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserverPolicyTest {

    private val prague = ZoneId.of("Europe/Prague")
    private val gps = GeoLocation(50.0755, 14.4378, 230.0)
    private val chile = SavedPlace("cl", "Paranal", -24.6272, -70.4042, "America/Santiago", 0L)

    @Test
    fun `GPS without a fix may not ask for a profile - the fallback must never become a skyline`() {
        val o = ObserverPolicy.observe(gps, hasGpsFix = false, place = null, deviceZone = prague)
        assertFalse(o.canRequestProfile)
        assertFalse(o.isVirtual)
        assertEquals(gps, o.location)
    }

    @Test
    fun `GPS with a fix keeps today's behaviour - camera, alignment, the phone's zone`() {
        val o = ObserverPolicy.observe(gps, hasGpsFix = true, place = null, deviceZone = prague)
        assertTrue(o.canRequestProfile)
        assertTrue(o.autoAlignAllowed)
        assertTrue(o.cameraWanted)
        assertEquals(prague, o.zone)
        assertNull(o.place)
    }

    @Test
    fun `a virtual place is exact - it asks for its profile without any GPS fix`() {
        val o = ObserverPolicy.observe(gps, hasGpsFix = false, place = chile, deviceZone = prague)
        assertTrue(o.isVirtual)
        assertTrue(o.canRequestProfile)
        assertEquals(chile.latitude, o.location.latitude, 0.0)
        assertEquals(chile.longitude, o.location.longitude, 0.0)
    }

    @Test
    fun `a virtual place never aligns against the camera and does not run it`() {
        val o = ObserverPolicy.observe(gps, hasGpsFix = true, place = chile, deviceZone = prague)
        assertFalse(o.autoAlignAllowed)
        assertFalse(o.cameraWanted)
    }

    @Test
    fun `times of a virtual place are in its own zone, not the phone's`() {
        val o = ObserverPolicy.observe(gps, hasGpsFix = true, place = chile, deviceZone = prague)
        assertEquals(ZoneId.of("America/Santiago"), o.zone)
    }

    @Test
    fun `a profile belongs to the observer it was computed for, to the store's 0_001 degree`() {
        val profile = profileAt(GeoLocation(50.0755, 14.4378))
        assertTrue(ObserverPolicy.profileBelongsTo(profile, GeoLocation(50.07551, 14.43779)))
        assertTrue("same rounded cell", ObserverPolicy.profileBelongsTo(profile, GeoLocation(50.0758, 14.4381)))
        assertFalse("another screen's place", ObserverPolicy.profileBelongsTo(profile, GeoLocation(50.7360, 15.7399)))
        assertFalse("two cells away", ObserverPolicy.profileBelongsTo(profile, GeoLocation(50.0775, 14.4378)))
    }

    private fun profileAt(observer: GeoLocation) = HorizonProfile(
        elevationsDegrees = FloatArray(HorizonProfile.SAMPLE_COUNT),
        observer = observer,
        observerGroundElevationMeters = 200.0,
        eyeHeightMeters = 1.6,
        maxRangeMeters = 60_000.0,
        generatedAtEpochMillis = 0L,
    )
}
