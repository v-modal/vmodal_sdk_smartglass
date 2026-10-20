package com.vmodal.smartglass.voicesearch.meta

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DisplaySubmissionTest {
    @Test fun lostAckRetriesSameOperationWithinDisplayDeadline() = runTest {
        var calls = 0
        var released = false
        val start = testScheduler.currentTime
        assertTrue(displaySubmission {
            if (++calls == 1) try { awaitCancellation() } finally { released = true }
            true
        })
        assertEquals(2, calls)
        assertTrue(released)
        assertEquals(2_000, testScheduler.currentTime - start)
    }
    @Test fun exhaustionAndExplicitRejectionNeverBecomeAccepted() = runTest {
        var calls = 0
        val start = testScheduler.currentTime
        assertFalse(displaySubmission { calls++; awaitCancellation() })
        assertEquals(2, calls)
        assertEquals(4_000, testScheduler.currentTime - start)
        calls = 0
        assertFalse(displaySubmission { calls++; false })
        assertEquals(1, calls)
    }
    @Test fun externalCancellationDoesNotRetry() = runTest {
        var calls = 0
        val task = launch { displaySubmission { calls++; awaitCancellation() } }
        runCurrent()
        task.cancelAndJoin()
        assertEquals(1, calls)
    }
}
