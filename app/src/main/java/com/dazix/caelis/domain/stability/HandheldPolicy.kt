package com.dazix.caelis.domain.stability

import com.dazix.caelis.domain.SessionState
import com.dazix.caelis.domain.TriggerMode

/**
 * When the "phone in hand" check runs and what it may do. It only ever warns: a session is never
 * paused or refused over it, because a hyperlapse shot on purpose from the hand is a real use and the
 * gyroscope cannot tell it from an accident.
 *
 * Only a [TriggerMode.Timelapse] drives a camera; a periodic tap is a metronome for whatever the user
 * aims at, and whether that is held in the hand is none of our business.
 */
object HandheldPolicy {

    /**
     * Whether the session should be listening to the gyroscope: while it is counting down to its
     * start and while it shoots. Not while paused (nothing is being shot, and a paused session is
     * usually one the user has picked up) and not once it is over.
     */
    fun watchDuringSession(state: SessionState, mode: TriggerMode?, enabled: Boolean): Boolean =
        enabled && mode is TriggerMode.Timelapse && when (state) {
            is SessionState.WaitingToStart, SessionState.Running -> true
            else -> false
        }

    /**
     * Whether the home screen should show the live indicator before a session is started. Takes the
     * setup's choice rather than a [TriggerMode]: before Start there is no config yet.
     */
    fun watchBeforeStart(sessionActive: Boolean, timelapse: Boolean, enabled: Boolean): Boolean =
        enabled && !sessionActive && timelapse

    /**
     * Whether pressing Start asks "start anyway?" first. Only on a positive answer: an unknown state
     * (no gyroscope, a window not yet full) starts straight away, never behind a question.
     */
    fun confirmStart(state: HandheldState, timelapse: Boolean, enabled: Boolean): Boolean =
        enabled && timelapse && state == HandheldState.HANDHELD
}
