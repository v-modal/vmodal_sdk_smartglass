package com.vmodal.smartglass.voicesearch.meta

import com.vmodal.smartglass.voicesearch.core.Action
import org.junit.Assert.*
import org.junit.Test

class MetaMappingTest {
    @Test fun missingConfidenceRemainsAbsent() {
        assertNull(confidence(-1f))
        assertNull(confidence(Float.NaN))
        assertEquals(0.5, confidence(0.5f)!!, 0.00001)
    }
    @Test fun allNeutralActionsHaveVisibleLabels() {
        assertEquals(listOf("New search", "Cancel", "Retry", "Dismiss"), Action.entries.map(::actionLabel))
    }
}
