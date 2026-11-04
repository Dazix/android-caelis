package com.dazix.caelis.domain.location

/** The two AR screens that can look at a virtual place. */
enum class PlaceScreen { SUN_AR, NIGHT_SKY }

/**
 * Which saved place each AR screen shows; `null` is the phone's own GPS position.
 *
 * By default the screens choose independently (planning a sunrise somewhere else must not move the
 * stars you are standing under). With [sync] on, both read and write [shared].
 */
data class PlaceSelection(
    val sync: Boolean = false,
    val shared: String? = null,
    val sunAr: String? = null,
    val nightSky: String? = null,
)

/**
 * Pure transitions of [PlaceSelection]. Toggling sync never changes what a screen shows at that
 * moment: switching it on adopts the choice of the screen the switch was pressed on, switching it
 * off hands the shared choice to both screens.
 */
object PlaceSelectionPolicy {

    fun selectedFor(selection: PlaceSelection, screen: PlaceScreen): String? =
        if (selection.sync) selection.shared else own(selection, screen)

    fun select(selection: PlaceSelection, screen: PlaceScreen, id: String?): PlaceSelection =
        when {
            selection.sync -> selection.copy(shared = id)
            screen == PlaceScreen.SUN_AR -> selection.copy(sunAr = id)
            else -> selection.copy(nightSky = id)
        }

    fun setSync(selection: PlaceSelection, sync: Boolean, fromScreen: PlaceScreen): PlaceSelection =
        when {
            sync == selection.sync -> selection
            sync -> selection.copy(sync = true, shared = own(selection, fromScreen))
            else -> selection.copy(sync = false, sunAr = selection.shared, nightSky = selection.shared)
        }

    /** A deleted place falls back to GPS everywhere it was chosen. */
    fun onPlaceDeleted(selection: PlaceSelection, id: String): PlaceSelection =
        selection.copy(
            shared = selection.shared.takeUnless { it == id },
            sunAr = selection.sunAr.takeUnless { it == id },
            nightSky = selection.nightSky.takeUnless { it == id },
        )

    /** Slots pointing at a place that no longer exists read as GPS. */
    fun resolve(selection: PlaceSelection, screen: PlaceScreen, places: List<SavedPlace>): SavedPlace? {
        val id = selectedFor(selection, screen) ?: return null
        return places.firstOrNull { it.id == id }
    }

    private fun own(selection: PlaceSelection, screen: PlaceScreen): String? =
        if (screen == PlaceScreen.SUN_AR) selection.sunAr else selection.nightSky
}
