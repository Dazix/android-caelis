package com.dazix.caelis.data.location

import android.content.Context
import android.util.Log
import com.dazix.caelis.data.horizon.inDebug
import com.dazix.caelis.data.horizon.logClass
import com.dazix.caelis.domain.location.PlaceScreen
import com.dazix.caelis.domain.location.PlaceSelection
import com.dazix.caelis.domain.location.PlaceSelectionPolicy
import com.dazix.caelis.domain.location.SavedPlace
import com.dazix.caelis.domain.location.SavedPlaceCodec
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The saved virtual places and which one each AR screen shows, in `filesDir/locations/`.
 *
 * Not in the settings DataStore: that one goes to the cloud backup and holds no position.
 * `locations/` is excluded from the cloud backup like `horizons/` (`data_extraction_rules.xml`).
 * A process singleton, like `HorizonRepository`: both AR screens read the same list, and a sync
 * switched on one screen has to be seen by the other at once.
 *
 * Nothing here logs a name or a coordinate; failures log the exception class only.
 */
class SavedPlaceRepository internal constructor(
    private val directory: File,
) {

    private val placesFile = File(directory, PLACES_FILE)
    private val selectionFile = File(directory, SELECTION_FILE)
    private val writeLock = Mutex()

    private val _places = MutableStateFlow(readPlaces())
    val places: StateFlow<List<SavedPlace>> = _places.asStateFlow()

    private val _selection = MutableStateFlow(readSelection())
    val selection: StateFlow<PlaceSelection> = _selection.asStateFlow()

    // Resolved in the same call that writes the list or the selection, not by a collector: a screen
    // that selects a place reads the new place on its very next line, and a test sees it too.
    private val placeFlows = PlaceScreen.entries.associateWith { screen ->
        MutableStateFlow(PlaceSelectionPolicy.resolve(_selection.value, screen, _places.value))
    }

    private fun resolveAll() {
        for ((screen, flow) in placeFlows) {
            flow.value = PlaceSelectionPolicy.resolve(_selection.value, screen, _places.value)
        }
    }

    /** The place [screen] shows, or null for GPS. */
    fun placeFor(screen: PlaceScreen): StateFlow<SavedPlace?> = placeFlows.getValue(screen).asStateFlow()

    /** Adds [place], or replaces the one with the same id (an edit). */
    suspend fun save(place: SavedPlace) = writeLock.withLock {
        val current = _places.value
        val index = current.indexOfFirst { it.id == place.id }
        val updated = if (index >= 0) current.toMutableList().also { it[index] = place } else current + place
        writePlaces(updated)
    }

    suspend fun delete(id: String) = writeLock.withLock {
        writePlaces(_places.value.filterNot { it.id == id })
        writeSelection(PlaceSelectionPolicy.onPlaceDeleted(_selection.value, id))
    }

    suspend fun select(screen: PlaceScreen, id: String?) = writeLock.withLock {
        writeSelection(PlaceSelectionPolicy.select(_selection.value, screen, id))
    }

    suspend fun setSync(sync: Boolean, fromScreen: PlaceScreen) = writeLock.withLock {
        writeSelection(PlaceSelectionPolicy.setSync(_selection.value, sync, fromScreen))
    }

    private fun readPlaces(): List<SavedPlace> = readText(placesFile)?.let(SavedPlaceCodec::decodePlaces) ?: emptyList()

    private fun readSelection(): PlaceSelection = readText(selectionFile)?.let(SavedPlaceCodec::decodeSelection) ?: PlaceSelection()

    private fun readText(file: File): String? = try {
        if (file.isFile) file.readText() else null
    } catch (e: IOException) {
        Log.w(TAG, "cannot read ${file.name}: ${e.logClass}", e.inDebug())
        null
    }

    private suspend fun writePlaces(list: List<SavedPlace>) {
        writeAtomically(placesFile, SavedPlaceCodec.encodePlaces(list))
        _places.value = list
        resolveAll()
    }

    private suspend fun writeSelection(selection: PlaceSelection) {
        if (selection == _selection.value) return
        writeAtomically(selectionFile, SavedPlaceCodec.encodeSelection(selection))
        _selection.value = selection
        resolveAll()
    }

    /** Temporary file + rename, as every other store here: a crash mid-write keeps the old list. */
    private suspend fun writeAtomically(target: File, content: String) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val temp = File.createTempFile(target.name, ".part", directory)
        try {
            temp.writeText(content)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("rename failed")
            }
        } catch (e: IOException) {
            Log.e(TAG, "cannot write ${target.name}: ${e.logClass}", e.inDebug())
            temp.delete()
            throw e
        }
    }

    companion object {
        private const val TAG = "SavedPlaces"
        const val DIRECTORY = "locations"
        private const val PLACES_FILE = "places.tsv"
        private const val SELECTION_FILE = "selection.tsv"

        @Volatile
        private var instance: SavedPlaceRepository? = null

        fun getInstance(context: Context): SavedPlaceRepository =
            instance ?: synchronized(this) {
                instance ?: SavedPlaceRepository(
                    File(context.applicationContext.filesDir, DIRECTORY),
                ).also { instance = it }
            }
    }
}
