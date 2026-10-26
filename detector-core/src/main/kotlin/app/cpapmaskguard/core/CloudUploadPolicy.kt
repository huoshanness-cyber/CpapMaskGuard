package app.cpapmaskguard.core

import java.time.Instant
import java.time.ZoneId

/**
 * 省流量的云端开关。
 * 连续 [MASK_OFF_STOP] 次云端 MASK_OFF 后停止再上传；
 * 每个 Asia/Shanghai 睡眠日（本地中午 12:00 换日，不是午夜）最多恢复 [DAILY_RESUME_LIMIT] 次。
 * 对准失败不是云端结果，不计入、也不打断这串 MASK_OFF。
 */
object CloudUploadPolicy {
    const val MASK_OFF_STOP = 10
    const val DAILY_RESUME_LIMIT = 10
    const val ZONE_ID = "Asia/Shanghai"

    private val zone: ZoneId = ZoneId.of(ZONE_ID)

    data class State(
        val consecutiveMaskOff: Int = 0,
        val uploadEnabled: Boolean = true,
        val resumesUsed: Int = 0,
        val resumeDay: String = "",
        val lastCloudWasMaskOff: Boolean = false,
    ) {
        fun remaining(limit: Int = DAILY_RESUME_LIMIT): Int = (limit - resumesUsed).coerceAtLeast(0)
    }

    sealed class Resume {
        abstract val state: State

        data class Accepted(override val state: State) : Resume()
        data class Denied(override val state: State) : Resume()
        data class AlreadyOn(override val state: State) : Resume()
    }

    /**
     * 睡眠日：上海时间中午 12:00 之前算前一天，避免一夜跨过零点就把恢复次数清掉。
     * 12:00 整点起算新的一天。
     */
    fun dayKey(epochMs: Long): String {
        val zoned = Instant.ofEpochMilli(epochMs).atZone(zone)
        val date = if (zoned.hour < 12) zoned.toLocalDate().minusDays(1) else zoned.toLocalDate()
        return date.toString()
    }

    /** 跨过上海睡眠日（中午）只清恢复次数，不停掉正在累计的脱落串，也不自动重新打开上传。 */
    fun roll(state: State, epochMs: Long): State {
        val today = dayKey(epochMs)
        if (state.resumeDay == today) return state
        return state.copy(resumeDay = today, resumesUsed = 0)
    }

    fun onAimInvalid(state: State, epochMs: Long): State = roll(state, epochMs)

    fun onCloudVote(state: State, vote: FrameVote, epochMs: Long): State {
        val rolled = roll(state, epochMs)
        if (!rolled.uploadEnabled) return rolled
        if (vote != FrameVote.CLOSER_TO_MASK_OFF) {
            return rolled.copy(consecutiveMaskOff = 0, lastCloudWasMaskOff = false)
        }
        val streak = rolled.consecutiveMaskOff + 1
        val stop = streak >= MASK_OFF_STOP
        return rolled.copy(
            consecutiveMaskOff = streak,
            uploadEnabled = !stop,
            lastCloudWasMaskOff = true,
        )
    }

    /**
     * 只有上传已关时才消耗次数。已经开着再点，不扣今日额度。
     * 恢复后清掉脱落串，下一轮重新数，避免一帧又被关上。
     */
    fun resume(state: State, epochMs: Long): Resume {
        val rolled = roll(state, epochMs)
        if (rolled.uploadEnabled) return Resume.AlreadyOn(rolled)
        if (rolled.resumesUsed >= DAILY_RESUME_LIMIT) return Resume.Denied(rolled)
        val next = rolled.copy(
            uploadEnabled = true,
            consecutiveMaskOff = 0,
            lastCloudWasMaskOff = false,
            resumesUsed = rolled.resumesUsed + 1,
        )
        return Resume.Accepted(next)
    }
}
