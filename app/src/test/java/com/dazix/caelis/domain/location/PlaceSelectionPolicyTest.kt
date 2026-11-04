package com.dazix.caelis.domain.location

import com.dazix.caelis.domain.location.PlaceScreen.NIGHT_SKY
import com.dazix.caelis.domain.location.PlaceScreen.SUN_AR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceSelectionPolicyTest {

    private val p = PlaceSelectionPolicy

    @Test
    fun `by default the two screens choose independently`() {
        val s = p.select(PlaceSelection(), SUN_AR, "alps")
        assertEquals("alps", p.selectedFor(s, SUN_AR))
        assertNull("Night Sky stays on GPS", p.selectedFor(s, NIGHT_SKY))
    }

    @Test
    fun `with sync on a choice on either screen is the choice of both`() {
        val synced = PlaceSelection(sync = true)
        val s = p.select(synced, NIGHT_SKY, "tatra")
        assertEquals("tatra", p.selectedFor(s, SUN_AR))
        assertEquals("tatra", p.selectedFor(s, NIGHT_SKY))
    }

    @Test
    fun `switching sync on adopts the choice of the screen it was pressed on`() {
        val s = PlaceSelection(sunAr = "alps", nightSky = "tatra")
        val fromNight = p.setSync(s, true, NIGHT_SKY)
        assertEquals("tatra", p.selectedFor(fromNight, SUN_AR))
        assertEquals("tatra", p.selectedFor(fromNight, NIGHT_SKY))
        val fromSun = p.setSync(s, true, SUN_AR)
        assertEquals("alps", p.selectedFor(fromSun, NIGHT_SKY))
    }

    @Test
    fun `switching sync off changes nothing either screen shows`() {
        val s = PlaceSelection(sync = true, shared = "alps", sunAr = "old", nightSky = null)
        val off = p.setSync(s, false, SUN_AR)
        assertEquals("alps", p.selectedFor(off, SUN_AR))
        assertEquals("alps", p.selectedFor(off, NIGHT_SKY))
        // ...and from then on they move apart again.
        assertNull(p.selectedFor(p.select(off, SUN_AR, null), SUN_AR))
        assertEquals("alps", p.selectedFor(p.select(off, SUN_AR, null), NIGHT_SKY))
    }

    @Test
    fun `setting sync to what it already is changes nothing`() {
        val s = PlaceSelection(sync = true, shared = "alps", sunAr = "x")
        assertEquals(s, p.setSync(s, true, SUN_AR))
    }

    @Test
    fun `a deleted place falls back to GPS wherever it was chosen`() {
        val s = PlaceSelection(sync = false, shared = "alps", sunAr = "alps", nightSky = "tatra")
        val after = p.onPlaceDeleted(s, "alps")
        assertEquals(PlaceSelection(sync = false, shared = null, sunAr = null, nightSky = "tatra"), after)
    }

    @Test
    fun `a slot pointing at a place that no longer exists resolves to GPS`() {
        val place = SavedPlace("alps", "Alps", 46.0, 8.0, "Europe/Zurich", 0L)
        val s = PlaceSelection(sunAr = "gone", nightSky = "alps")
        assertNull(p.resolve(s, SUN_AR, listOf(place)))
        assertEquals(place, p.resolve(s, NIGHT_SKY, listOf(place)))
    }
}
