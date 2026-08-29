package com.yahyapro20.dshmobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Phase {
    IDLE, BOOTSTRAPPING, STARTING_DSH, RUNNING, ERROR
}

data class Status(
    val phase: Phase = Phase.IDLE,
    val message: String = "",
    val errorDetail: String? = null
)

/**
 * Process-wide (in-memory only) status holder. HarnessService writes to it,
 * MainActivity observes it. Deliberately simple for the MVP — no persistence,
 * no cross-process IPC, just a StateFlow inside the same process.
 */
object HarnessState {
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    fun update(phase: Phase, message: String, errorDetail: String? = null) {
        _status.value = Status(phase, message, errorDetail)
    }
}
