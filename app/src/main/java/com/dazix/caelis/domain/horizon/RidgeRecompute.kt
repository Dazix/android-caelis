package com.dazix.caelis.domain.horizon

/**
 * Whether a stored profile is computed again to give it ridges.
 *
 * A profile from before ridges existed (codec v3 and older) has a good skyline and no ridges. It is
 * recomputed only when every elevation tile it needs is still on the device: a profile is the
 * artefact that works with no signal, and a ridge drawing is not worth a download of up to 26 MB a
 * tile, or a consent prompt. Without the tiles it stays as it is and is drawn as one ring.
 */
object RidgeRecompute {
    fun needed(stored: HorizonProfile, missingTiles: Int): Boolean = stored.ridges == null && missingTiles == 0
}
