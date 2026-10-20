package com.vmodal.smartglass.voicesearch.meta

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** DAT 1.0.1 sends before registering its ACK waiter. Retry only a timed-out idempotent operation. */
internal suspend fun displaySubmission(operation: suspend () -> Boolean): Boolean {
    repeat(2) {
        val result = withTimeoutOrNull(2_000) { operation() }
        currentCoroutineContext().ensureActive()
        if (result != null) return result
    }
    return false
}
