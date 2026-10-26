package app.cpapmaskguard.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudCircuitTest {
    @Test
    fun twoFailuresOpenLocalFirstAndALaterProbe() {
        val t0 = 1_000_000L
        var state = CloudCircuit.State()
        assertEquals(CloudCircuit.Action.CALL, CloudCircuit.action(state, t0))

        state = CloudCircuit.onFailure(state, t0)
        assertEquals(1, state.consecutiveFailures)
        assertEquals(CloudCircuit.Action.CALL, CloudCircuit.action(state, t0 + 1))

        state = CloudCircuit.onFailure(state, t0 + 1_000)
        assertTrue(state.openUntilEpochMs > t0)
        assertEquals(CloudCircuit.Action.LOCAL, CloudCircuit.action(state, t0 + 1_000))
        assertEquals(CloudCircuit.Action.LOCAL, CloudCircuit.action(state, t0 + 30_000))

        val probeAt = state.lastProbeEpochMs + CloudCircuit.PROBE_INTERVAL_MS
        assertEquals(CloudCircuit.Action.PROBE, CloudCircuit.action(state, probeAt))
        state = CloudCircuit.noteProbe(state, probeAt)
        assertEquals(CloudCircuit.Action.LOCAL, CloudCircuit.action(state, probeAt + 1))
    }

    @Test
    fun aSuccessfulProbeClosesTheCircuit() {
        var state = CloudCircuit.onFailure(CloudCircuit.State(), 0)
        state = CloudCircuit.onFailure(state, 1_000)
        state = CloudCircuit.onSuccess(state)
        assertEquals(0, state.consecutiveFailures)
        assertEquals(0L, state.openUntilEpochMs)
        assertEquals(CloudCircuit.Action.CALL, CloudCircuit.action(state, 2_000))
    }

    @Test
    fun aFailedProbeKeepsLocalFirstForAnotherWindow() {
        var state = CloudCircuit.onFailure(CloudCircuit.State(), 0)
        state = CloudCircuit.onFailure(state, 1_000)
        val probeAt = state.lastProbeEpochMs + CloudCircuit.PROBE_INTERVAL_MS
        state = CloudCircuit.noteProbe(state, probeAt)
        state = CloudCircuit.onFailure(state, probeAt + 12_000)
        assertEquals(CloudCircuit.Action.LOCAL, CloudCircuit.action(state, probeAt + 12_000))
        assertTrue(state.openUntilEpochMs >= probeAt + 12_000 + CloudCircuit.LOCAL_FIRST_MS - 1)
    }
}
