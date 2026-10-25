package app.cpapmaskguard.core

/**
 * 相机票 + 本地鼾声确认的融合。
 * 用户约定：持续打呼基本说明面罩未就位；音频用于触发或加快确认，
 * 不把总时延拖过约 10–30 秒目标。
 */
object SnoreFusion {
    /** 仅音频、相机未明确「戴着」时，确认鼾声多久可报警。 */
    const val AUDIO_ALARM_MS_WHEN_NOT_ON = 6_000L

    /** 相机明确「更像戴着」时，仅音频仍要更长确认，减少误报。 */
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
    ): Result {
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
