package app.cpapmaskguard.core

/**
 * 上传停着的时候，画面相对暂停瞬间的粗亮度格发生明显变化，
 * 并且连续多帧都变了，才自动恢复上传一次。同一次暂停里只触发一次。
 */
object SceneChange {
    const val CONFIRM_FRAMES = 3
    const val MIN_MEAN_ABS_DELTA = 0.12f

    data class State(
        val baseline: List<Float> = emptyList(),
        val streak: Int = 0,
        val usedThisPause: Boolean = false,
    )

    data class Step(val state: State, val resume: Boolean)

    fun observe(state: State, signature: List<Float>): Step {
        if (signature.isEmpty() || state.usedThisPause) return Step(state, false)
        if (state.baseline.isEmpty() || state.baseline.size != signature.size) {
            return Step(State(baseline = signature), false)
        }
        val delta = meanAbsDelta(state.baseline, signature)
        if (delta < MIN_MEAN_ABS_DELTA) {
            return Step(state.copy(streak = 0), false)
        }
        val streak = state.streak + 1
        if (streak >= CONFIRM_FRAMES) {
            return Step(state.copy(streak = streak, usedThisPause = true), true)
        }
        return Step(state.copy(streak = streak), false)
    }

    fun meanAbsDelta(baseline: List<Float>, signature: List<Float>): Float {
        if (baseline.isEmpty() || baseline.size != signature.size) return 0f
        var sum = 0f
        for (index in baseline.indices) {
            sum += kotlin.math.abs(baseline[index] - signature[index])
        }
        return sum / baseline.size
    }
}
