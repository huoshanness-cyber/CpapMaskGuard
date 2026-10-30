package app.cpapmaskguard.core

/**
 * 相机票 + 本地鼾声确认的融合。
 * 启发式打呼把对话当成鼾声，误报已经不能用。云端看图是主路径。
 * [AUDIO_ALARM_ENABLED] 默认关：声判不响铃，报警原因也不会写成「音频确认打呼」。
 * 时长和融合算法留着，以后换成准模型时把开关打开即可。
 */
object SnoreFusion {
    /**
     * 听鼾旁路报警。默认 false。
     * 为 true 时才允许「只靠声音」响铃，并在原因里写音频确认。
     */
    const val AUDIO_ALARM_ENABLED = false

    /** 仅音频、相机未明确「戴着」时，确认鼾声多久可报警。开关打开后才用。 */
    const val AUDIO_ALARM_MS_WHEN_NOT_ON = 6_000L

    /** 相机明确「更像戴着」时，仅音频仍要更长确认，减少误报。开关打开后才用。 */
    const val AUDIO_ALARM_MS_WHEN_ON = 12_000L

    data class Result(
        val shouldAlarm: Boolean,
        val reason: String,
        val audioAssisted: Boolean,
    )

    fun fuse(
        cameraShouldAlarm: Boolean,
        cameraVote: FrameVote?,
        snoreConfirmed: Boolean,
        snoreConfirmedForMs: Long,
        cameraSummary: String,
        audioAlarmEnabled: Boolean = AUDIO_ALARM_ENABLED,
    ): Result {
        if (!audioAlarmEnabled) {
            return Result(
                shouldAlarm = cameraShouldAlarm,
                reason = cameraSummary,
                audioAssisted = false,
            )
        }
        if (cameraShouldAlarm) {
            return Result(
                shouldAlarm = true,
                reason = cameraSummary,
                audioAssisted = snoreConfirmed,
            )
        }
        if (!snoreConfirmed) {
            return Result(false, cameraSummary, false)
        }
        val need = when (cameraVote) {
            FrameVote.CLOSER_TO_MASK_ON -> AUDIO_ALARM_MS_WHEN_ON
            else -> AUDIO_ALARM_MS_WHEN_NOT_ON
        }
        if (snoreConfirmedForMs >= need) {
            val voteLabel = cameraVote?.name ?: "无相机票"
            return Result(
                shouldAlarm = true,
                reason = "音频确认打呼 ${snoreConfirmedForMs / 1000L}s（面罩可能未就位）· 相机:$voteLabel · $cameraSummary",
                audioAssisted = true,
            )
        }
        return Result(
            shouldAlarm = false,
            reason = "$cameraSummary · 打呼中 ${snoreConfirmedForMs / 1000L}s/${need / 1000L}s",
            audioAssisted = true,
        )
    }
}
