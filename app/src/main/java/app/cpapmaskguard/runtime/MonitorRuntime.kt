package app.cpapmaskguard.runtime

import kotlinx.coroutines.flow.MutableStateFlow

enum class RunStatus {
    STOPPED,
    STARTING,
    MONITORING,
    ALARMING,
    ERROR,
}

data class MonitorSnapshot(
    val status: RunStatus = RunStatus.STOPPED,
    val headline: String = "还没开始",
    val detail: String = "睡前摆好手机，对准枕头，再点开始守护。",
    val nightExtension: Boolean? = null,
    /** 最近一轮成功拍照的时间（epoch ms），0 表示还没有。 */
    val lastFrameEpochMs: Long = 0L,
    /** 本轮监测已成功处理的帧数。 */
    val roundCount: Int = 0,
    /** 最近一轮检测摘要（可用 MaskDetector.summary）。 */
    val lastScoreHint: String? = null,
    /** 听鼾状态摘要。 */
    val snoreHint: String? = null,
    /** 预览用短文案：面罩正常 / 面罩脱落 / … */
    val previewResultLabel: String? = null,
    /** 最近抓拍结果日志（新在前），最多 [CAPTURE_LOG_MAX] 条。 */
    val captureLog: List<String> = emptyList(),
    /**
     * 躺好缓冲结束的 elapsedRealtime。0 表示当前不在缓冲里。
     * 界面用它每秒刷新「还剩 Xs」，不依赖抓拍间隔。
     */
    val settleUntilElapsed: Long = 0L,
    /**
     * 下一轮开拍的墙钟时间。0 表示当前没有倒计时。
     * 界面用它每秒刷新「下一张 N 秒」。
     */
    val nextShotEpochMs: Long = 0L,
    /** 这一轮的判断过程：正在拍摄 / 上传中 / 上传成功，等待结果 / 结果或失败原因。 */
    val judgePhase: String? = null,
)

/**
 * 界面和服务在同一个进程里共享状态。进程被系统杀掉之后，这里会回到「还没开始」，
 * 主界面再根据「上次请求过监测、但服务已经不在」提示可能被清掉了。
 */
object MonitorRuntime {
    const val CAPTURE_LOG_MAX = 30

    val snapshot = MutableStateFlow(MonitorSnapshot())

    fun update(block: (MonitorSnapshot) -> MonitorSnapshot) {
        snapshot.value = block(snapshot.value)
    }

    fun appendCaptureLog(line: String) {
        update { snap ->
            val next = (listOf(line) + snap.captureLog).take(CAPTURE_LOG_MAX)
            snap.copy(captureLog = next)
        }
    }
}
