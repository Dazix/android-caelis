package com.dazix.caelis.data.location

import com.dazix.caelis.domain.location.PlaceScreen
import com.dazix.caelis.domain.location.SavedPlace
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SavedPlaceRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val alps = SavedPlace("alps", "Alps", 46.5, 8.0, "Europe/Zurich", 1L)
    private val tatra = SavedPlace("tatra", "Tatra", 49.2, 20.0, "Europe/Bratislava", 2L)

    private fun repo(dir: File) = SavedPlaceRepository(dir)

    @Test
    fun `places and the selection survive a new process`() = runTest {
        val dir = File(folder.root, "locations")
        val first = repo(dir)
        first.save(alps)
        first.save(tatra)
        first.select(PlaceScreen.SUN_AR, "alps")
        first.setSync(true, PlaceScreen.NIGHT_SKY)

        val second = repo(dir)
        assertEquals(listOf(alps, tatra), second.places.value)
        assertTrue(second.selection.value.sync)
        // Sync was switched on from Night Sky, which showed GPS: both screens now show GPS.
        assertNull(second.placeFor(PlaceScreen.SUN_AR).value)
        assertNull(second.placeFor(PlaceScreen.NIGHT_SKY).value)
    }

    @Test
    fun `saving an existing id edits the place in place`() = runTest {
        val r = repo(File(folder.root, "locations"))
        r.save(alps)
        r.save(tatra)
        r.save(alps.copy(name = "Matterhorn"))
        assertEquals(listOf("Matterhorn", "Tatra"), r.places.value.map { it.name })
    }

    @Test
    fun `deleting the shown place falls back to GPS on that screen`() = runTest {
        val r = repo(File(folder.root, "locations"))
        r.save(alps)
        r.select(PlaceScreen.NIGHT_SKY, "alps")
        assertEquals(alps, r.placeFor(PlaceScreen.NIGHT_SKY).value)
        r.delete("alps")
        assertNull(r.placeFor(PlaceScreen.NIGHT_SKY).value)
        assertNull(r.selection.value.nightSky)
    }

    @Test
    fun `no partial file is left behind after a write`() = runTest {
        val dir = File(folder.root, "locations")
        val r = repo(dir)
        r.save(alps)
        r.select(PlaceScreen.SUN_AR, "alps")
        val names = dir.list().orEmpty().toSet()
        assertEquals(setOf("places.tsv", "selection.tsv"), names)
        assertFalse(names.any { it.endsWith(".part") })
    }

    @Test
    fun `a missing directory reads as no places and GPS everywhere`() = runTest {
        val r = repo(File(folder.root, "nothing-here"))
        assertEquals(emptyList<SavedPlace>(), r.places.value)
        assertNull(r.placeFor(PlaceScreen.SUN_AR).value)
    }
}
