package com.dazix.intervalometer.data

import com.dazix.intervalometer.domain.SessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide hand-off between the accessibility service (writer) and the UI (reader).
 * Both live in the same process, so no IPC is involved.
 */
object SessionRepository {

    private val mutableSnapshot = MutableStateFlow(SessionSnapshot())

    val snapshot: StateFlow<SessionSnapshot> = mutableSnapshot.asStateFlow()

    /** Applies [transform] atomically; it may be invoked more than once under contention. */
    fun update(transform: (SessionSnapshot) -> SessionSnapshot) {
        mutableSnapshot.update(transform)
    }

    fun reset() {
        mutableSnapshot.value = SessionSnapshot()
    }
}
