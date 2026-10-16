package com.dazix.caelis.domain.arframe

/**
 * Tells the newest asynchronous request from every earlier one, so that a late answer or a stale timer
 * cannot act on behalf of a request that is no longer the one being waited for.
 *
 * Automatic alignment has three things racing for one UI state: the camera frame (which may arrive
 * after a camera hand-over, or never), a timeout that gives up on it, and the user tapping the button
 * again. With a bare "is it still Working?" check an old timer failed a newer request after a fraction
 * of its own window, and a frame that arrived after the timeout was still processed and applied behind
 * a "no camera" message. Every request takes a token from [begin]; whoever wants to act for it asks
 * [isCurrent] first and settles it with [finish], which succeeds exactly once and only for the newest.
 *
 * Main thread only, like everything that touches the state it protects.
 */
class LatestRequestGate {
    private var newest = 0
    private var open = false

    /** Starts a request; every earlier token stops being current. */
    fun begin(): Int {
        newest++
        open = true
        return newest
    }

    /** Whether [token] is the newest request and has not yet been settled. */
    fun isCurrent(token: Int): Boolean = open && token == newest

    /**
     * Settles [token]. True exactly once, for the newest request, so that the result, the timeout and
     * the cancellation cannot all apply.
     */
    fun finish(token: Int): Boolean {
        if (!isCurrent(token)) return false
        open = false
        return true
    }

    /** Abandons whatever is outstanding without starting anything (a request refused before it began). */
    fun invalidate() {
        newest++
        open = false
    }
}
