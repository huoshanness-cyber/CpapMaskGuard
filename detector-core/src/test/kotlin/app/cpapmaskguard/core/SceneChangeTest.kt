package app.cpapmaskguard.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SceneChangeTest {
    private val dark = List(4) { 0.1f }
    private val bright = List(4) { 0.4f }

    @Test
    fun threeConfirmedFramesResumeOncePerPause() {
        var state = SceneChange.State()
        val armed = SceneChange.observe(state, dark)
        state = armed.state
        assertFalse(armed.resume)
        assertEquals(dark, state.baseline)

        val first = SceneChange.observe(state, bright)
        val second = SceneChange.observe(first.state, bright)
        assertFalse(first.resume)
        assertFalse(second.resume)
        assertEquals(2, second.state.streak)

        val third = SceneChange.observe(second.state, bright)
        assertTrue(third.resume)
        assertTrue(third.state.usedThisPause)

        val again = SceneChange.observe(third.state, List(4) { 0.9f })
        assertFalse(again.resume)
    }

    @Test
    fun aQuietFrameResetsTheConfirmStreak() {
        var state = SceneChange.observe(SceneChange.State(), dark).state
        state = SceneChange.observe(state, bright).state
        state = SceneChange.observe(state, dark).state
        assertEquals(0, state.streak)
        val next = SceneChange.observe(state, bright)
        assertFalse(next.resume)
        assertEquals(1, next.state.streak)
    }

    @Test
    fun anAutoResumeStillSpendsOneDailyResume() {
        val paused = CloudUploadPolicy.State(
            uploadEnabled = false,
            lastCloudWasMaskOff = true,
            resumesUsed = 4,
            resumeDay = "2026-10-25",
        )
        val evening = java.time.Instant.parse("2026-10-25T15:00:00Z").toEpochMilli()
        val outcome = CloudUploadPolicy.resume(paused, evening)
        assertTrue(outcome is CloudUploadPolicy.Resume.Accepted)
        assertEquals(5, outcome.state.resumesUsed)
        assertTrue(outcome.state.uploadEnabled)
    }
}
