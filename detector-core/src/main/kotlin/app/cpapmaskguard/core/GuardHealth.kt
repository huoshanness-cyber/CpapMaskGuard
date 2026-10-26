package app.cpapmaskguard.core

/**
 * 守护是不是还在出结果。和前台/后台标志无关：只看失败时间和上次判定时刻。
 * 约 [WINDOW_MS] 内累计 [FAILURES_TO_TRIP] 次判定失败，或相机绑定失败，
 * 或超过 [STALL_INTERVALS] 个抓拍间隔没有新结果，就该提醒。
 */
object GuardHealth {
    const val WINDOW_MS = 30_000L
    const val FAILURES_TO_TRIP = 3
    const val STALL_INTERVALS = 3

    enum class Reason {
        JUDGMENT,
        CAMERA,
        STALL,
    }

    data class State(
        val failureEpochMs: List<Long> = emptyList(),
        val tripped: Boolean = false,
        val reason: Reason? = null,
    )

    data class Step(
        val state: State,
        val justTripped: Boolean,
        val reason: Reason? = null,
    )

    fun onJudgmentFailure(state: State, nowMs: Long): Step {
        val recent = (state.failureEpochMs + nowMs).filter { nowMs - it <= WINDOW_MS }
        if (recent.size >= FAILURES_TO_TRIP) {
            val just = !state.tripped
            return Step(
                state = State(failureEpochMs = recent, tripped = true, reason = Reason.JUDGMENT),
                justTripped = just,
                reason = if (just) Reason.JUDGMENT else null,
            )
        }
        return Step(state.copy(failureEpochMs = recent), justTripped = false)
    }

    /** 相机绑定失败立刻提醒，不等满三次。 */
    fun onCameraBindFailure(state: State): Step {
        return Step(
            state = state.copy(tripped = true, reason = Reason.CAMERA),
            justTripped = true,
            reason = Reason.CAMERA,
        )
    }

    /**
     * @param lastJudgmentElapsedMs 上次真正给出判定的开机时间。0 表示还没有过判定，先不叫停。
     * 超过 3 个间隔才算停住；刚好等于 3 个间隔仍算在时限内。
     */
    fun onTick(
        state: State,
        lastJudgmentElapsedMs: Long,
        nowElapsedMs: Long,
        intervalMs: Long,
    ): Step {
        if (lastJudgmentElapsedMs <= 0L || intervalMs <= 0L) return Step(state, false)
        val stalled = nowElapsedMs - lastJudgmentElapsedMs > STALL_INTERVALS * intervalMs
        if (!stalled) {
            if (state.reason == Reason.STALL) {
                return Step(state.copy(tripped = false, reason = null), false)
            }
            return Step(state, false)
        }
        if (state.tripped && state.reason != null) {
            return Step(state, false)
        }
        return Step(
            state = state.copy(tripped = true, reason = Reason.STALL),
            justTripped = true,
            reason = Reason.STALL,
        )
    }

    /** 出了新结果就清掉失败窗，停住提醒也可以再响一次。 */
    fun onJudgment(state: State): State = State()
}
