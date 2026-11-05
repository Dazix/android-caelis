package com.dazix.caelis.data.nightsky

import android.content.res.Resources
import com.dazix.caelis.R
import com.dazix.caelis.domain.nightsky.SkyNames

/**
 * Loads [SkyNames] for the language the resources are configured for.
 *
 * `R.raw.sky_names` resolves like any resource: `raw-de` for de-AT, `raw` (English) for a language
 * without a file of its own. The result is cached per locale, and a changed app language (the
 * view model outlives the activity that was recreated for it) reloads instead of keeping the old
 * names. Reading is a few kilobytes, done when a scene or the search index is rebuilt, never per
 * frame.
 */
object SkyNamesResource {

    @Volatile
    private var cached: Pair<String, SkyNames>? = null

    fun forResources(resources: Resources): SkyNames {
        val tag = resources.configuration.locales[0].toLanguageTag()
        cached?.let { (cachedTag, names) -> if (cachedTag == tag) return names }
        val names = resources.openRawResource(R.raw.sky_names).bufferedReader(Charsets.UTF_8).use {
            SkyNames.parse(it.readText())
        }
        cached = tag to names
        return names
    }
}
