package app.cpapmaskguard.core

/**
 * 云端连续失败后的本机优先窗口。
 * 打开后不再每帧干等云端超时；每隔 [PROBE_INTERVAL_MS] 才探一次，成功就合上。
 */
object CloudCircuit {
    const val OPEN_AFTER = 2
    const val LOCAL_FIRST_MS = 5 * 60_000L
    const val PROBE_INTERVAL_MS = 60_000L

    data class State(
        val consecutiveFailures: Int = 0,
        val openUntilEpochMs: Long = 0L,
        val lastProbeEpochMs: Long = 0L,
    )

    enum class Action { CALL, LOCAL, PROBE }

    fun action(state: State, nowMs: Long): Action {
        if (nowMs >= state.openUntilEpochMs) return Action.CALL
        if (nowMs - state.lastProbeEpochMs >= PROBE_INTERVAL_MS) return Action.PROBE
        return Action.LOCAL
    }

    fun onSuccess(state: State): State = State()

    fun onFailure(state: State, nowMs: Long): State {
        val fails = state.consecutiveFailures + 1
        if (fails >= OPEN_AFTER) {
            return State(
                consecutiveFailures = fails,
                openUntilEpochMs = nowMs + LOCAL_FIRST_MS,
                lastProbeEpochMs = nowMs,
            )
        }
        return state.copy(consecutiveFailures = fails)
    }

    /** 探活发出去之前先记账，避免这一轮还没回来又被下一帧当成该探了。 */
    fun noteProbe(state: State, nowMs: Long): State = state.copy(lastProbeEpochMs = nowMs)
}
