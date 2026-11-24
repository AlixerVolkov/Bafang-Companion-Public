package dev.alixer.bafang.protocol

import org.junit.Assert.*
import org.junit.Test

class AssistSelectionTest {
    @Test fun directSelectionCoversAllSixStates() {
        val state=AssistSelection()
        for(level in 0..5) { state.request(level,1000L+level); assertEquals(level,state.level) }
        assertEquals(5,state.pendingLevel)
    }
    @Test fun staleFeedbackCannotResetRapidPlusSequence() {
        val state=AssistSelection()
        state.request(1,1000)
        state.request(2,1100)
        assertEquals(AssistSelection.Feedback.STALE_IGNORED,state.observe(0,1200))
        assertEquals(2,state.level)
        state.request(3,1300)
        assertEquals(AssistSelection.Feedback.STALE_IGNORED,state.observe(1,1400))
        assertEquals(3,state.level)
    }
    @Test fun matchingFeedbackConfirmsAndLaterFeedbackUpdates() {
        val state=AssistSelection()
        state.request(4,2000)
        assertEquals(AssistSelection.Feedback.CONFIRMED,state.observe(4,2100))
        assertNull(state.pendingLevel)
        assertEquals(AssistSelection.Feedback.REPORTED,state.observe(5,2400))
        assertEquals(5,state.level)
    }
    @Test fun mismatchAfterTimeoutShowsActualDisplay() {
        val state=AssistSelection()
        state.request(5,1000)
        assertEquals(AssistSelection.Feedback.DIFFERENT_AFTER_TIMEOUT,state.observe(1,5000))
        assertEquals(1,state.level)
        assertNull(state.pendingLevel)
    }
    @Test fun invalidLevelIsNeverAccepted() {
        val state=AssistSelection()
        state.request(2,0)
        assertEquals(AssistSelection.Feedback.INVALID,state.observe(17,300))
        assertEquals(2,state.level)
    }
    @Test fun resetClearsPending() {
        val state=AssistSelection()
        state.request(4,1200)
        state.reset()
        assertEquals(0,state.level)
        assertNull(state.pendingLevel)
    }
}