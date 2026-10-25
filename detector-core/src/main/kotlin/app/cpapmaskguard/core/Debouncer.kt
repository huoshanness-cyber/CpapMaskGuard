package app.cpapmaskguard.core

/**
 * 连续确认。默认 [DetectionConfig.DEBOUNCE_CONSECUTIVE]=1，即单帧「更像没戴」即可响铃，
 * 以满足约 10–30 秒内叫醒的时效（再叠加抓拍间隔）。
 * 「无人」会清票且不报警。「看不清」连续太多次也会清票。
 */
class Debouncer(
    private val required: Int = DetectionConfig.DEBOUNCE_CONSECUTIVE,
    private val maxUncertainStreak: Int = DetectionConfig.MAX_UNCERTAIN_STREAK,
) {
    var maskOffStreak: Int = 0
        private set

    var uncertainStreak: Int = 0
        private set

    /** 只有「刚刚凑满」的那一帧返回 true。凑满后要 [reset]，才会再计下一轮。 */
    fun onVote(vote: FrameVote): Boolean {
        val before = maskOffStreak
        when (vote) {
            FrameVote.CLOSER_TO_MASK_OFF -> {
                if (maskOffStreak < required) maskOffStreak += 1
                uncertainStreak = 0
            }
            FrameVote.CLOSER_TO_MASK_ON, FrameVote.NO_PERSON -> {
                maskOffStreak = 0
                uncertainStreak = 0
            }
            FrameVote.UNCERTAIN -> {
                uncertainStreak += 1
                if (uncertainStreak >= maxUncertainStreak) {
                    maskOffStreak = 0
                }
            }
        }
        return before < required && maskOffStreak >= required
    }

    fun reset() {
        maskOffStreak = 0
        uncertainStreak = 0
    }
}
