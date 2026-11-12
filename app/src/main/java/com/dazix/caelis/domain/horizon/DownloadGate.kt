package com.dazix.caelis.domain.horizon

/** How the user lets the app fetch elevation tiles. */
enum class DownloadMode {
    /** No network at all; tiles come only from a hand import. */
    NEVER,

    /** Nothing is fetched without a tap; the screen offers the download with its size. */
    ON_DEMAND,

    /** Fetched without asking up to the limit, offered like [ON_DEMAND] above it. */
    AUTOMATIC,
}

/**
 * Whether a run of tile downloads may start now, must wait for a tap, or may not happen at all.
 *
 * Sizes come from a HEAD request per tile before anything is fetched, so the decision is made on the
 * real compressed sizes and not on a per-tile guess, which would be off by a factor of ten between a
 * plain (1–2 MB) and a mountain range (up to 19 MB).
 */
object DownloadGate {

    /** A size the server did not announce. */
    const val UNKNOWN: Long = -1L

    sealed interface Decision {
        data object Proceed : Decision

        /**
         * Wait for a tap. [knownBytes] is the sum of the sizes that are known; [unknownTiles] were not
         * announced and are not in it, so with any of them the true total is "at least" [knownBytes].
         */
        data class Ask(val tiles: Int, val knownBytes: Long, val unknownTiles: Int) : Decision

        data object Refuse : Decision
    }

    /**
     * @param limitBytes the automatic mode's limit; null is "unlimited", which proceeds without asking
     *   even for unannounced sizes, because the user chose not to be asked.
     * @param alreadyBytes what this run has already downloaded (the observer's tile, before the ring
     *   an automatic radius asks for), so the limit holds for the run and not per stage.
     * @param remainingSizes the announced size of every tile still to fetch, [UNKNOWN] where none was.
     * @param confirmed the user has already tapped Download for this run.
     */
    fun decide(
        mode: DownloadMode,
        limitBytes: Long?,
        alreadyBytes: Long,
        remainingSizes: List<Long>,
        confirmed: Boolean,
    ): Decision {
        if (mode == DownloadMode.NEVER) return Decision.Refuse
        if (remainingSizes.isEmpty() || confirmed) return Decision.Proceed

        val known = remainingSizes.filter { it >= 0 }
        val ask = Decision.Ask(
            tiles = remainingSizes.size,
            knownBytes = known.sum(),
            unknownTiles = remainingSizes.size - known.size,
        )
        if (mode == DownloadMode.ON_DEMAND) return ask
        if (limitBytes == null) return Decision.Proceed
        // An unannounced size could be anything up to a whole mountain tile; counting it as small is
        // exactly the silent large download the limit exists to stop.
        if (ask.unknownTiles > 0) return ask
        return if (alreadyBytes + ask.knownBytes <= limitBytes) Decision.Proceed else ask
    }
}
