package com.dazix.caelis.data.horizon

import android.content.Context
import android.text.format.Formatter
import com.dazix.caelis.R

/**
 * What a run waiting for a tap would download: "3 tiles, 41 MB", or "at least 12 MB" when a size was
 * not announced or an automatic radius may still add the observer's neighbours. Never a guess shown
 * as an exact number.
 */
fun HorizonState.AwaitingConfirmation.offerText(context: Context): String {
    val size = when {
        knownBytes <= 0L -> context.getString(R.string.horizon_size_unknown)
        unknownTiles > 0 || moreMayFollow -> context.getString(
            R.string.horizon_size_at_least,
            Formatter.formatShortFileSize(context, knownBytes),
        )
        else -> Formatter.formatShortFileSize(context, knownBytes)
    }
    return context.resources.getQuantityString(R.plurals.horizon_offer_tiles, tiles, tiles, size)
}

/**
 * How much of the whole run has crossed the network, "4.1 MB of 12 MB", or only "4.1 MB" while a
 * size in it was not announced. Compressed bytes, as on the wire; locale-formatted.
 */
fun HorizonState.Downloading.sizeText(context: Context): String {
    val received = Formatter.formatShortFileSize(context, receivedBytes)
    if (totalBytes <= 0) return received
    return context.getString(
        R.string.horizon_download_size_of,
        received,
        Formatter.formatShortFileSize(context, totalBytes),
    )
}
