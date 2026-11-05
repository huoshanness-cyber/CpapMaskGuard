package app.cpapmaskguard.core

/**
 * 连续确认。默认 [DetectionConfig.DEBOUNCE_CONSECUTIVE]=3。
 * 单帧或双帧「更像没戴」不响铃，避免玩手机贴脸 / 侧睡挡脸被云端误判成脱落就立刻叫。
 * 「无人」与「看不清」都会清票且不报警；UNCERTAIN 不计入脱落票。
 */
class Debouncer(
    private val required: Int = DetectionConfig.DEBOUNCE_CONSECUTIVE,
) {
    var maskOffStreak: Int = 0
        private set

    /** 只有「刚刚凑满」的那一帧返回 true。凑满后要 [reset]，才会再计下一轮。 */
    fun onVote(vote: FrameVote): Boolean {
        val before = maskOffStreak
        when (vote) {
            FrameVote.CLOSER_TO_MASK_OFF -> {
                if (maskOffStreak < required) maskOffStreak += 1
            }
            FrameVote.CLOSER_TO_MASK_ON, FrameVote.NO_PERSON, FrameVote.UNCERTAIN -> {
                maskOffStreak = 0
            }
        }
        return before < required && maskOffStreak >= required
    }

    fun reset() {
        maskOffStreak = 0
    }
}
