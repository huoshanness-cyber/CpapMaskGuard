package app.cpapmaskguard.burnin

import kotlinx.coroutines.flow.MutableStateFlow

enum class BurnInPhase {
    IDLE,
    RUNNING,
    COMPLETED,
    CANCELLED,
    ERROR,
}

data class BurnInReport(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val cancelled: Boolean,
    val plannedDurationMs: Long,
    val sampleCount: Int,
    val scoreStart: Int?,
    val scoreEnd: Int?,
    val scoreMin: Int?,
    val everBelow60: Boolean,
    val tempMinC: Float?,
    val tempAvgC: Float?,
    val tempMaxC: Float?,
    val latencyMinMs: Long?,
    val latencyAvgMs: Long?,
    val latencyMaxMs: Long?,
    val pssMinMb: Float?,
    val pssAvgMb: Float?,
    val pssMaxMb: Float?,
    val availEndMb: Float?,
    val manufacturer: String,
    val model: String,
    val device: String,
    val brand: String,
    val androidRelease: String,
    val sdkInt: Int,
    val appVersionName: String,
    val appVersionCode: String,
    val fillLightMode: String,
    val intervalSec: Int,
    val captureWidth: Int,
    val captureHeight: Int,
    val errorHint: String? = null,
)

data class BurnInSnapshot(
    val phase: BurnInPhase = BurnInPhase.IDLE,
    val elapsedMs: Long = 0L,
    val durationMs: Long = BurnInRuntime.DEFAULT_DURATION_MS,
    val sampleCount: Int = 0,
    val currentScore: Int? = null,
    val currentTempC: Float? = null,
    val currentCaptureToJudgeMs: Long? = null,
    val progressLine: String = "",
    val detailLine: String = "使用前建议先烤机 5 分钟，测完会预填邮件发回报告。",
    val report: BurnInReport? = null,
    /** 递增令牌，便于界面只处理一次「结束发邮件」。 */
    val finishToken: Long = 0L,
)

/**
 * 烤机进度与报告，供主界面与 [app.cpapmaskguard.service.BurnInService] 共享。
 */
object BurnInRuntime {
    /** 默认烤机时长：5 分钟。 */
    const val DEFAULT_DURATION_MS = 5 * 60 * 1000L

    val snapshot = MutableStateFlow(BurnInSnapshot())

    fun update(block: (BurnInSnapshot) -> BurnInSnapshot) {
        snapshot.value = block(snapshot.value)
    }

    fun resetIdle(detail: String? = null) {
        snapshot.value = BurnInSnapshot(
            detailLine = detail
                ?: "使用前建议先烤机 5 分钟，测完会预填邮件发回报告。",
        )
    }
}
