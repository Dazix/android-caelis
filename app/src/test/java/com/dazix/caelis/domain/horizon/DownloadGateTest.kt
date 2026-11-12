package com.dazix.caelis.domain.horizon

import com.dazix.caelis.domain.horizon.DownloadGate.Decision
import com.dazix.caelis.domain.horizon.DownloadGate.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadGateTest {

    private val mb = 1_000_000L
    private val limit = 50 * mb

    private fun decide(
        mode: DownloadMode = DownloadMode.AUTOMATIC,
        already: Long = 0L,
        sizes: List<Long>,
        confirmed: Boolean = false,
    ) = DownloadGate.decide(mode, limit, already, sizes, confirmed)

    @Test
    fun `never refuses even a confirmed run`() {
        assertEquals(Decision.Refuse, decide(DownloadMode.NEVER, sizes = listOf(mb)))
        assertEquals(Decision.Refuse, decide(DownloadMode.NEVER, sizes = listOf(mb), confirmed = true))
    }

    @Test
    fun `automatic proceeds up to and including the limit`() {
        assertEquals(Decision.Proceed, decide(sizes = listOf(20 * mb, 30 * mb)))
    }

    @Test
    fun `automatic asks one byte over the limit, with the run's size`() {
        assertEquals(
            Decision.Ask(tiles = 2, knownBytes = 50 * mb + 1, unknownTiles = 0),
            decide(sizes = listOf(20 * mb, 30 * mb + 1)),
        )
    }

    @Test
    fun `the limit counts what the run already downloaded`() {
        // The observer's tile came first (an automatic radius); the ring alone is under the limit.
        assertEquals(
            Decision.Ask(tiles = 2, knownBytes = 30 * mb, unknownTiles = 0),
            decide(already = 25 * mb, sizes = listOf(15 * mb, 15 * mb)),
        )
        assertEquals(Decision.Proceed, decide(already = 20 * mb, sizes = listOf(15 * mb, 15 * mb)))
    }

    @Test
    fun `an unannounced size is never taken for a small one`() {
        assertEquals(
            Decision.Ask(tiles = 2, knownBytes = mb, unknownTiles = 1),
            decide(sizes = listOf(mb, UNKNOWN)),
        )
    }

    @Test
    fun `on demand asks even for a small run`() {
        assertEquals(
            Decision.Ask(tiles = 1, knownBytes = mb, unknownTiles = 0),
            decide(DownloadMode.ON_DEMAND, sizes = listOf(mb)),
        )
    }

    @Test
    fun `a tap confirms the run in both modes, whatever its size`() {
        assertEquals(Decision.Proceed, decide(DownloadMode.ON_DEMAND, sizes = listOf(mb), confirmed = true))
        assertEquals(Decision.Proceed, decide(sizes = listOf(200 * mb, UNKNOWN), confirmed = true))
    }

    @Test
    fun `nothing to fetch is nothing to ask about`() {
        assertEquals(Decision.Proceed, decide(DownloadMode.ON_DEMAND, sizes = emptyList()))
    }
}
