package com.dazix.caelis.overlay

import com.dazix.caelis.domain.SessionSnapshot
import com.dazix.caelis.domain.SessionState

/**
 * The two timing/state decisions the floating panel makes, kept free of Android types so they can be
 * unit tested: when the screen has to stay on, and when the panel has to stop accepting touches.
 */
object OverlayPolicy {

    /**
     * The cadence the cloak window is sized against: the last interval the session actually reported,
     * remembered across the zeroes.
     *
     * `SessionSnapshot.lastIntervalMs` is the *achieved* interval and legitimately drops to 0 mid-run,
     * so it cannot be read directly — 0 means "this shot's target was not after the last tap", not
     * "this session has no cadence". Remembering the last positive value keeps a fast session fast:
     * one slipped shot must not hand a 300 ms periodic tap a 2.1 s untouchable window.
     */
    class Cadence {

        var lastKnownMs: Long = 0L
            private set

        /** Feeds a snapshot in and returns the cadence to use for it. */
        fun observe(snapshot: SessionSnapshot): Long {
            val reported = snapshot.lastIntervalMs
            if (reported > 0L) lastKnownMs = reported
            return lastKnownMs
        }

        fun reset() {
            lastKnownMs = 0L
        }
    }

    /**
     * How long before the predicted shot the panel goes untouchable. A window relayout is one frame;
     * 600 ms is three orders of magnitude of slack, and the flag is armed by a timer that fires at
     * this boundary rather than by the one-second render tick.
     */
    const val CLOAK_LEAD_MS = 600L

    /**
     * How long after the predicted shot the panel stays untouchable. Covers a late alarm, the settle
     * delay some camera profiles insert, and the retry the engine may fire after a cancelled gesture.
     */
    const val CLOAK_TAIL_MS = 1_500L

    /**
     * `FLAG_KEEP_SCREEN_ON` belongs to a session that is *actually shooting*. Holding it through
     * `WaitingToStart` and `Paused` is what turned an overnight time-lapse into an all-night lit
     * display in an app that advertises auto-stop on low battery.
     */
    fun keepScreenOn(state: SessionState, optionEnabled: Boolean): Boolean =
        optionEnabled && state is SessionState.Running

    /**
     * The one warning line the panel, the notification and the home screen show. The session's own
     * [SessionSnapshot.warning] wins: it says why nothing is being shot (screen off, wrong app) or
     * that a shot failed, which outranks a shaky frame. Only without it does a raised
     * [SessionSnapshot.handheld] put [handheldText] there, and it goes as soon as the hand does.
     */
    fun warningText(snapshot: SessionSnapshot, handheldText: String): String? =
        snapshot.warning?.takeIf { it.isNotBlank() }
            ?: handheldText.takeIf { snapshot.handheld }

    /**
     * True when the floating panel must stand down because the app's own UI is the thing on screen.
     *
     * The panel exists to control a session running *inside another app*. Over our own screens it is
     * pure harm: it hides the cards it is floating on, it duplicates controls that are already there in
     * full size, and it is the same mistake as drawing the calibration overlay over our own UI. An
     * unknown foreground (`null`) leaves the panel up — the session is presumably still driving some
     * other app, and hiding the only visible stop button on a guess would be worse.
     */
    fun suppressForOwnUi(foregroundPackage: String?, selfPackage: String): Boolean =
        foregroundPackage != null && foregroundPackage == selfPackage

    /**
     * The largest share of one interval the panel may spend untouchable. Above this the cloak stops
     * being defence in depth and becomes the dominant state: the user cannot reach Stop, and worse,
     * every touch aimed at it falls through to whatever the app underneath has at that spot.
     */
    private const val CLOAK_BUDGET_NUMERATOR = 1L
    private const val CLOAK_BUDGET_DENOMINATOR = 2L

    /**
     * Below this the window is not worth arming at all: a cloak has to cover the dispatched gesture
     * (70 ms) plus a window relayout to buy anything, and a shorter one only costs touches.
     */
    private const val CLOAK_MIN_USEFUL_MS = 200L

    /**
     * How long around a predicted shot the panel gives up its touches, for a session shooting every
     * [intervalMs]. `lead to tail`, both zero when cloaking is skipped entirely.
     *
     * [CLOAK_LEAD_MS] + [CLOAK_TAIL_MS] is 2.1 s, which is fine for a camera app (its own capture
     * latency is of that order) and catastrophic for a fast periodic tap, whose floor is one tap
     * duration: at a 300 ms interval the full window never closes, so the panel is untouchable for the
     * whole session — Pause and Stop simply do not respond, and a touch on Stop is delivered to the app
     * behind it instead. The window is therefore scaled to fit inside half the interval, and dropped
     * below [CLOAK_MIN_USEFUL_MS], where [PanelPlacement]'s guarantee — the panel is never *put* over a
     * pixel a shutter tap can land on — carries the whole job on its own.
     */
    fun cloakWindowMs(intervalMs: Long): Pair<Long, Long> {
        val full = CLOAK_LEAD_MS + CLOAK_TAIL_MS
        // A non-positive cadence is not information. It happens mid-run — `achievedIntervalMs` reports
        // 0 whenever the next target is at or before the last tap, which a shot that slipped by more
        // than one interval produces — and answering it with the *widest* window is how a 300 ms
        // periodic tap ended up with a permanently untouchable panel again. The caller passes the last
        // cadence it actually saw ([Cadence]); reaching this branch means there has never been
        // one, i.e. nothing has been dispatched yet, and the full window costs nothing.
        if (intervalMs <= 0L) return CLOAK_LEAD_MS to CLOAK_TAIL_MS
        val budget = intervalMs * CLOAK_BUDGET_NUMERATOR / CLOAK_BUDGET_DENOMINATOR
        if (budget >= full) return CLOAK_LEAD_MS to CLOAK_TAIL_MS
        if (budget < CLOAK_MIN_USEFUL_MS) return 0L to 0L
        val lead = budget * CLOAK_LEAD_MS / full
        return lead to (budget - lead)
    }

    /**
     * True while a synthetic shutter tap may be in flight, i.e. while the panel must not be able to
     * swallow one. A `Running` session with no scheduled next shot means a shot is being taken right
     * now, which is the most dangerous moment of all, so it counts as cloaked — unless the interval is
     * so short that [cloakWindowMs] has switched cloaking off, in which case there is no moment the
     * panel would ever be touchable and the placement guarantee is what protects the tap.
     */
    fun isCloaked(snapshot: SessionSnapshot, nowElapsed: Long, cadenceMs: Long): Boolean {
        if (snapshot.state !is SessionState.Running) return false
        val (lead, tail) = cloakWindowMs(cadenceMs)
        if (lead == 0L && tail == 0L) return false
        val next = snapshot.nextShotAtElapsed ?: return true
        return nowElapsed >= next - lead && nowElapsed <= next + tail
    }

    /**
     * Milliseconds until [isCloaked] can flip on its own, or `null` when only a new snapshot can
     * change it. Used to arm an exact timer instead of relying on the one-second render tick.
     */
    fun nextCloakChangeDelayMs(snapshot: SessionSnapshot, nowElapsed: Long, cadenceMs: Long): Long? {
        if (snapshot.state !is SessionState.Running) return null
        val (lead, tail) = cloakWindowMs(cadenceMs)
        if (lead == 0L && tail == 0L) return null
        val next = snapshot.nextShotAtElapsed ?: return null
        val opens = next - lead
        val closes = next + tail
        return when {
            nowElapsed < opens -> opens - nowElapsed
            nowElapsed <= closes -> closes - nowElapsed + 1L
            else -> null
        }
    }
}
