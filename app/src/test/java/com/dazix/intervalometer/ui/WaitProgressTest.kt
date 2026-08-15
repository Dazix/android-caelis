package com.dazix.intervalometer.ui

import com.dazix.intervalometer.domain.SessionSnapshot
import com.dazix.intervalometer.domain.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The fraction the session indicator on the home screen shows.
 *
 * The regression: it divided the remaining time by `lastIntervalMs` in every state, while the engine
 * seeds that field with the *planned* interval before any shot exists. With the app's defaults — a 5 s
 * start delay and a 10 s interval — the ring was therefore already half full the moment Start was
 * pressed, and with a delay longer than the interval it sat pinned at zero and then jumped.
 */
class WaitProgressTest {

    private val setup = SetupUiState(
        intervalValue = "10",
        intervalUnit = TimeUnitChoice.SECONDS,
        startDelayValue = "5",
        startDelayUnit = TimeUnitChoice.SECONDS,
    )

    private fun assertProgress(expected: Float, actual: Float) {
        assertEquals(expected.toDouble(), actual.toDouble(), 0.001)
    }

    @Test
    fun `the start delay is measured against the start delay, not the interval`() {
        val startsAt = 100_000L
        val snapshot = SessionSnapshot(
            state = SessionState.WaitingToStart(startsAtElapsed = startsAt),
            // What the engine publishes before the first shot: the planned interval.
            lastIntervalMs = 10_000L,
            shotsTaken = 0,
        )
        // The instant Start is pressed, nothing has elapsed.
        assertProgress(0f, waitProgress(snapshot, setup, startsAt - 5_000L))
        assertProgress(0.5f, waitProgress(snapshot, setup, startsAt - 2_500L))
        assertProgress(1f, waitProgress(snapshot, setup, startsAt))
    }

    @Test
    fun `a delay longer than the interval still fills exactly once`() {
        val longDelay = setup.copy(startDelayValue = "60")
        val startsAt = 100_000L
        val snapshot = SessionSnapshot(
            state = SessionState.WaitingToStart(startsAtElapsed = startsAt),
            lastIntervalMs = 10_000L,
        )
        assertProgress(0f, waitProgress(snapshot, longDelay, startsAt - 60_000L))
        assertProgress(0.5f, waitProgress(snapshot, longDelay, startsAt - 30_000L))
        assertProgress(1f, waitProgress(snapshot, longDelay, startsAt))
    }

    @Test
    fun `the first interval uses the planned one, later intervals the achieved one`() {
        val next = 200_000L
        val beforeFirstShot = SessionSnapshot(
            state = SessionState.Running,
            nextShotAtElapsed = next,
            lastIntervalMs = 10_000L,
            shotsTaken = 0,
        )
        assertProgress(0.5f, waitProgress(beforeFirstShot, setup, next - 5_000L))

        // Once shots exist the achieved interval is the honest denominator, even when it differs from
        // the planned one — a camera that needed 7.9 s for a 6 s request is the normal case.
        val afterShots = beforeFirstShot.copy(shotsTaken = 3, lastIntervalMs = 8_000L)
        assertProgress(0.5f, waitProgress(afterShots, setup, next - 4_000L))
    }

    @Test
    fun `no scheduled shot and no usable duration mean no progress`() {
        assertProgress(
            0f,
            waitProgress(SessionSnapshot(state = SessionState.Running), setup, 1_000L),
        )
        assertProgress(
            0f,
            waitProgress(
                SessionSnapshot(state = SessionState.Running, nextShotAtElapsed = 5_000L),
                setup.copy(intervalValue = "0"),
                1_000L,
            ),
        )
    }

    @Test
    fun `a late shot clamps at full rather than overshooting`() {
        val snapshot = SessionSnapshot(
            state = SessionState.Running,
            nextShotAtElapsed = 100_000L,
            lastIntervalMs = 10_000L,
            shotsTaken = 2,
        )
        assertProgress(1f, waitProgress(snapshot, setup, 100_400L))
    }
}
