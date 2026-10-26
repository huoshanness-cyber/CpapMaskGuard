package app.cpapmaskguard.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudUploadPolicyTest {
    private val evening = Instant.parse("2026-10-25T15:00:00Z").toEpochMilli()
    private val nextShanghaiMidnight = Instant.parse("2026-10-25T16:00:00Z").toEpochMilli()

    @Test
    fun dayKeyUsesShanghaiCalendar() {
        assertEquals("2026-10-25", CloudUploadPolicy.dayKey(evening))
        assertEquals("2026-10-26", CloudUploadPolicy.dayKey(nextShanghaiMidnight))
    }

    @Test
    fun tenthCloudMaskOffStopsUploadAndKeepsTheAlarmFact() {
        var state = CloudUploadPolicy.State()
        repeat(9) {
            state = CloudUploadPolicy.onCloudVote(state, FrameVote.CLOSER_TO_MASK_OFF, evening)
        }
        assertTrue(state.uploadEnabled)
        assertEquals(9, state.consecutiveMaskOff)
        state = CloudUploadPolicy.onCloudVote(state, FrameVote.CLOSER_TO_MASK_OFF, evening)
        assertFalse(state.uploadEnabled)
        assertEquals(10, state.consecutiveMaskOff)
        assertTrue(state.lastCloudWasMaskOff)
    }

    @Test
    fun aimMissDoesNotCountOrResetTheMaskOffStreak() {
        var state = CloudUploadPolicy.State()
        repeat(9) {
            state = CloudUploadPolicy.onCloudVote(state, FrameVote.CLOSER_TO_MASK_OFF, evening)
            state = CloudUploadPolicy.onAimInvalid(state, evening)
        }
        assertEquals(9, state.consecutiveMaskOff)
        assertTrue(state.uploadEnabled)
        state = CloudUploadPolicy.onCloudVote(state, FrameVote.CLOSER_TO_MASK_OFF, evening)
        assertFalse(state.uploadEnabled)
    }

    @Test
    fun otherCloudLabelsResetTheStreak() {
        var state = CloudUploadPolicy.onCloudVote(
            CloudUploadPolicy.State(consecutiveMaskOff = 4, lastCloudWasMaskOff = true),
            FrameVote.CLOSER_TO_MASK_ON,
            evening,
        )
        assertEquals(0, state.consecutiveMaskOff)
        assertFalse(state.lastCloudWasMaskOff)

        state = CloudUploadPolicy.onCloudVote(
            CloudUploadPolicy.State(consecutiveMaskOff = 4),
            FrameVote.NO_PERSON,
            evening,
        )
        assertEquals(0, state.consecutiveMaskOff)

        state = CloudUploadPolicy.onCloudVote(
            CloudUploadPolicy.State(consecutiveMaskOff = 4),
            FrameVote.UNCERTAIN,
            evening,
        )
        assertEquals(0, state.consecutiveMaskOff)
        assertTrue(state.uploadEnabled)
    }

    @Test
    fun votesAfterStopDoNotReopenUpload() {
        val stopped = CloudUploadPolicy.State(
            consecutiveMaskOff = 10,
            uploadEnabled = false,
            lastCloudWasMaskOff = true,
            resumeDay = "2026-10-25",
        )
        val next = CloudUploadPolicy.onCloudVote(stopped, FrameVote.CLOSER_TO_MASK_ON, evening)
        assertFalse(next.uploadEnabled)
        assertEquals(10, next.consecutiveMaskOff)
        assertTrue(next.lastCloudWasMaskOff)
    }

    @Test
    fun resumeConsumesOneAndClearsTheStreak() {
        val stopped = CloudUploadPolicy.State(
            consecutiveMaskOff = 10,
            uploadEnabled = false,
            lastCloudWasMaskOff = true,
            resumesUsed = 2,
            resumeDay = "2026-10-25",
        )
        val outcome = CloudUploadPolicy.resume(stopped, evening)
        assertTrue(outcome is CloudUploadPolicy.Resume.Accepted)
        assertTrue(outcome.state.uploadEnabled)
        assertEquals(0, outcome.state.consecutiveMaskOff)
        assertFalse(outcome.state.lastCloudWasMaskOff)
        assertEquals(3, outcome.state.resumesUsed)
        assertEquals(7, outcome.state.remaining())
    }

    @Test
    fun resumeWhileAlreadyOnDoesNotSpendQuota() {
        val on = CloudUploadPolicy.State(uploadEnabled = true, resumesUsed = 1, resumeDay = "2026-10-25")
        val outcome = CloudUploadPolicy.resume(on, evening)
        assertTrue(outcome is CloudUploadPolicy.Resume.AlreadyOn)
        assertEquals(1, outcome.state.resumesUsed)
    }

    @Test
    fun eleventhResumeOfTheDayIsDenied() {
        var state = CloudUploadPolicy.State(uploadEnabled = false, lastCloudWasMaskOff = true, resumeDay = "2026-10-25")
        repeat(10) {
            state = state.copy(uploadEnabled = false, lastCloudWasMaskOff = true)
            val outcome = CloudUploadPolicy.resume(state, evening)
            assertTrue(outcome is CloudUploadPolicy.Resume.Accepted)
            state = outcome.state
        }
        state = state.copy(uploadEnabled = false, lastCloudWasMaskOff = true)
        val denied = CloudUploadPolicy.resume(state, evening)
        assertTrue(denied is CloudUploadPolicy.Resume.Denied)
        assertFalse(denied.state.uploadEnabled)
        assertEquals(0, denied.state.remaining())
    }

    @Test
    fun shanghaiMidnightRestoresTheResumeQuotaWithoutReopeningUpload() {
        val stopped = CloudUploadPolicy.State(
            consecutiveMaskOff = 10,
            uploadEnabled = false,
            lastCloudWasMaskOff = true,
            resumesUsed = 10,
            resumeDay = "2026-10-25",
        )
        val rolled = CloudUploadPolicy.roll(stopped, nextShanghaiMidnight)
        assertEquals(0, rolled.resumesUsed)
        assertEquals("2026-10-26", rolled.resumeDay)
        assertFalse(rolled.uploadEnabled)
        assertEquals(10, rolled.consecutiveMaskOff)
        val outcome = CloudUploadPolicy.resume(stopped, nextShanghaiMidnight)
        assertTrue(outcome is CloudUploadPolicy.Resume.Accepted)
        assertEquals(1, outcome.state.resumesUsed)
    }
}
