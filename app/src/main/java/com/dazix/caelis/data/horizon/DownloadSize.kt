package com.dazix.caelis.data.horizon

import android.content.Context
import android.text.format.Formatter
import com.dazix.caelis.R

/**
 * How much of the current tile has crossed the network, "4.1 MB of 12 MB", or only "4.1 MB" while
 * the server has not said how large it is. Compressed bytes, as on the wire; locale-formatted.
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
