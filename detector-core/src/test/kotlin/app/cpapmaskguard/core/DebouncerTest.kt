package app.cpapmaskguard.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebouncerTest {
    @Test
    fun alarmsAfterRequiredConsecutiveMaskOffVotes() {
        val debouncer = Debouncer()
        // Default is 2: one MASK_OFF must not ring.
        assertFalse(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
        assertEquals(1, debouncer.maskOffStreak)
        assertTrue(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
        assertEquals(DetectionConfig.DEBOUNCE_CONSECUTIVE, debouncer.maskOffStreak)
        assertFalse(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
    }

    @Test
    fun maskOnClearsTheStreak() {
        val debouncer = Debouncer(required = 2)
        debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF)
        debouncer.onVote(FrameVote.CLOSER_TO_MASK_ON)
        assertEquals(0, debouncer.maskOffStreak)
        assertFalse(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
    }

    @Test
    fun noPersonClearsTheStreakAndDoesNotAlarm() {
        val debouncer = Debouncer(required = 2)
        debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF)
        assertFalse(debouncer.onVote(FrameVote.NO_PERSON))
        assertEquals(0, debouncer.maskOffStreak)
    }

    @Test
    fun shortUncertainRunKeepsExistingVotes() {
        val debouncer = Debouncer(required = 3, maxUncertainStreak = 4)
        debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF)
        debouncer.onVote(FrameVote.UNCERTAIN)
        debouncer.onVote(FrameVote.UNCERTAIN)
        assertEquals(1, debouncer.maskOffStreak)
        debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF)
        assertTrue(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
    }

    @Test
    fun longUncertainRunDropsExistingVotes() {
        val debouncer = Debouncer(required = 2)
        debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF)
        repeat(DetectionConfig.MAX_UNCERTAIN_STREAK) {
            debouncer.onVote(FrameVote.UNCERTAIN)
        }
        assertEquals(0, debouncer.maskOffStreak)
    }

    @Test
    fun resetStartsOver() {
        val debouncer = Debouncer()
        repeat(DetectionConfig.DEBOUNCE_CONSECUTIVE) {
            debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF)
        }
        debouncer.reset()
        assertEquals(0, debouncer.maskOffStreak)
        assertFalse(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
        assertTrue(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
    }
}
