package app.cpapmaskguard.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuardHealthTest {
    @Test
    fun threeJudgmentFailuresInsideThirtySecondsTripOnce() {
        var state = GuardHealth.State()
        val first = GuardHealth.onJudgmentFailure(state, 1_000)
        state = first.state
        assertFalse(first.justTripped)
        val second = GuardHealth.onJudgmentFailure(state, 10_000)
        state = second.state
        assertFalse(second.justTripped)
        val third = GuardHealth.onJudgmentFailure(state, 20_000)
        assertTrue(third.justTripped)
        assertEquals(GuardHealth.Reason.JUDGMENT, third.reason)
        val again = GuardHealth.onJudgmentFailure(third.state, 21_000)
        assertFalse(again.justTripped)
        assertTrue(again.state.tripped)
    }

    @Test
    fun failuresOlderThanTheWindowDoNotCount() {
        var state = GuardHealth.State()
        state = GuardHealth.onJudgmentFailure(state, 0).state
        state = GuardHealth.onJudgmentFailure(state, 40_000).state
        val third = GuardHealth.onJudgmentFailure(state, 50_000)
        assertFalse(third.justTripped)
        assertEquals(2, third.state.failureEpochMs.size)
    }

    @Test
    fun aJudgmentClearsTheFailureWindow() {
        var state = GuardHealth.onJudgmentFailure(GuardHealth.State(), 0).state
        state = GuardHealth.onJudgmentFailure(state, 1_000).state
        state = GuardHealth.onJudgment(state)
        val next = GuardHealth.onJudgmentFailure(state, 2_000)
        assertFalse(next.justTripped)
        assertEquals(1, next.state.failureEpochMs.size)
    }

    @Test
    fun cameraBindFailureTripsImmediately() {
        val step = GuardHealth.onCameraBindFailure(GuardHealth.State())
        assertTrue(step.justTripped)
        assertEquals(GuardHealth.Reason.CAMERA, step.reason)
    }

    @Test
    fun stallUsesElapsedIntervalsAndIgnoresAnyForegroundFlag() {
        val interval = 8_000L
        val last = 1_000L
        val exact = GuardHealth.onTick(
            GuardHealth.State(),
            lastJudgmentElapsedMs = last,
            nowElapsedMs = last + interval * GuardHealth.STALL_INTERVALS,
            intervalMs = interval,
        )
        assertFalse(exact.justTripped)
        assertNull(exact.reason)

        val over = GuardHealth.onTick(
            GuardHealth.State(),
            lastJudgmentElapsedMs = last,
            nowElapsedMs = last + interval * GuardHealth.STALL_INTERVALS + 1,
            intervalMs = interval,
        )
        assertTrue(over.justTripped)
        assertEquals(GuardHealth.Reason.STALL, over.reason)

        val held = GuardHealth.onTick(
            over.state,
            lastJudgmentElapsedMs = last,
            nowElapsedMs = last + interval * 4,
            intervalMs = interval,
        )
        assertFalse(held.justTripped)

        val fresh = GuardHealth.onTick(GuardHealth.State(), 0L, 99_000L, interval)
        assertFalse(fresh.justTripped)
    }

    @Test
    fun anOpenJudgmentAlertIsNotRaisedAgainAsAStall() {
        var state = GuardHealth.State()
        state = GuardHealth.onJudgmentFailure(state, 0).state
        state = GuardHealth.onJudgmentFailure(state, 1_000).state
        state = GuardHealth.onJudgmentFailure(state, 2_000).state
        assertTrue(state.tripped)
        val stall = GuardHealth.onTick(state, lastJudgmentElapsedMs = 1L, nowElapsedMs = 100_000L, intervalMs = 8_000L)
        assertFalse(stall.justTripped)
        assertEquals(GuardHealth.Reason.JUDGMENT, stall.state.reason)
    }
}
