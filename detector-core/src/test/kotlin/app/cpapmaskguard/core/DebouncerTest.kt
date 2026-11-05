package app.cpapmaskguard.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebouncerTest {
    @Test
    fun alarmsAfterRequiredConsecutiveMaskOffVotes() {
        val debouncer = Debouncer()
        // Default is 3: one or two MASK_OFF must not ring.
        assertFalse(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
        assertEquals(1, debouncer.maskOffStreak)
        assertFalse(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
        assertEquals(2, debouncer.maskOffStreak)
        assertTrue(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
        assertEquals(DetectionConfig.DEBOUNCE_CONSECUTIVE, debouncer.maskOffStreak)
        assertFalse(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
    }

    @Test
    fun maskOnClearsTheStreak() {
        val debouncer = Debouncer(required = 3)
        debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF)
        debouncer.onVote(FrameVote.CLOSER_TO_MASK_ON)
        assertEquals(0, debouncer.maskOffStreak)
        assertFalse(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
    }

    @Test
    fun noPersonClearsTheStreakAndDoesNotAlarm() {
        val debouncer = Debouncer(required = 3)
        debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF)
        assertFalse(debouncer.onVote(FrameVote.NO_PERSON))
        assertEquals(0, debouncer.maskOffStreak)
    }

    @Test
    fun uncertainClearsExistingVotesImmediately() {
        val debouncer = Debouncer(required = 3)
        debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF)
        debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF)
        assertEquals(2, debouncer.maskOffStreak)
        assertFalse(debouncer.onVote(FrameVote.UNCERTAIN))
        assertEquals(0, debouncer.maskOffStreak)
        // OFF after UNCERTAIN starts a new streak; still needs 3.
        assertFalse(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
        assertFalse(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
        assertTrue(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
    }

    @Test
    fun twoMaskOffFramesDoNotAlarm() {
        val debouncer = Debouncer()
        assertFalse(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
        assertFalse(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
        assertEquals(2, debouncer.maskOffStreak)
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
        assertFalse(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
        assertTrue(debouncer.onVote(FrameVote.CLOSER_TO_MASK_OFF))
    }
}
