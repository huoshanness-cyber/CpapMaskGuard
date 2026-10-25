package app.cpapmaskguard.core

/**
 * 轻量本地鼾声启发：不联网、无模型。
 * 输入为 PCM 16-bit mono 一段样本（建议 8–16 kHz）。
 * 特征：RMS 能量 + 低频带能量占比 + 过零率（鼾声偏低频、周期性、ZCR 偏低）。
 */
object SnoreHeuristics {
    /** 相对满幅 (32768) 的 RMS 下限，过低当作环境静音。 */
    const val MIN_RMS = 0.018f

    /** 低频能量 / 总能量下限（粗略：|x[i]-x[i-1]| 小 → 低频多）。 */
    const val MIN_LOW_RATIO = 0.55f

    /** 过零率上限（相对样本对数），鼾声通常低于清辅音/音乐。 */
    const val MAX_ZCR = 0.22f

    /** 连续多少个分析窗判为鼾声样，才进入「确认鼾声」。 */
    const val CONFIRM_WINDOWS = 4

    /** 最近窗口滑动里至少多少个阳性才保持确认（滞后）。 */
    const val HOLD_WINDOWS = 3

    /** 滑窗长度。 */
    const val HISTORY = 8

    data class FrameFeatures(
        val rms: Float,
        val lowRatio: Float,
        val zcr: Float,
        val snoreLike: Boolean,
    )

    fun analyze(pcm: ShortArray, length: Int = pcm.size): FrameFeatures {
        val n = length.coerceIn(0, pcm.size)
        if (n < 32) {
            return FrameFeatures(0f, 0f, 0f, false)
        }
        var sumSq = 0.0
        var lowEnergy = 0.0
        var totalDiff = 0.0
        var zeroCross = 0
        var prev = pcm[0].toInt()
        for (i in 0 until n) {
            val v = pcm[i].toInt()
            val fv = v / 32768.0
            sumSq += fv * fv
            val diff = kotlin.math.abs(v - prev)
            totalDiff += diff
            // 一阶差分小 → 偏低频；把「平滑能量」记入 lowEnergy
            val smooth = (v + prev) / 2.0 / 32768.0
            lowEnergy += smooth * smooth
            if ((prev >= 0 && v < 0) || (prev < 0 && v >= 0)) zeroCross++
            prev = v
        }
        val rms = kotlin.math.sqrt(sumSq / n).toFloat()
        val lowRatio = if (sumSq <= 1e-12) 0f else (lowEnergy / sumSq).toFloat().coerceIn(0f, 2f)
        val zcr = (zeroCross.toFloat() / (n - 1).toFloat()).coerceIn(0f, 1f)
        // lowRatio 用平滑能量比总能量，常 >1 不太有意义；改用差分相对能量启发式：
        val meanAbsDiff = (totalDiff / n / 32768.0).toFloat()
        val spectralFlat = if (rms < 1e-6f) 1f else (meanAbsDiff / (rms + 1e-6f)).coerceIn(0f, 10f)
        // 鼾声：有一定响度、差分不过大（非高频噪声）、ZCR 不太高
        val snoreLike = rms >= MIN_RMS &&
            zcr <= MAX_ZCR &&
            spectralFlat <= 2.8f &&
            lowRatio >= MIN_LOW_RATIO
        return FrameFeatures(rms, lowRatio, zcr, snoreLike)
    }

    /**
     * 滑动确认状态机。调用方每分析一窗喂入 [snoreLike]。
     */
    class Tracker(
        private val confirmNeed: Int = CONFIRM_WINDOWS,
        private val holdNeed: Int = HOLD_WINDOWS,
        private val history: Int = HISTORY,
    ) {
        private val recent = ArrayDeque<Boolean>(history)
        var confirmed: Boolean = false
            private set
        var confirmedSinceElapsedMs: Long = 0L
            private set
        private var confirmStartedElapsed: Long = -1L

        fun onWindow(snoreLike: Boolean, elapsedRealtimeMs: Long): Boolean {
            if (recent.size >= history) recent.removeFirst()
            recent.addLast(snoreLike)
            val positives = recent.count { it }
            if (!confirmed) {
                if (positives >= confirmNeed && recent.takeLast(confirmNeed).all { it }) {
                    confirmed = true
                    confirmStartedElapsed = elapsedRealtimeMs
                }
            } else {
                if (positives < holdNeed) {
                    confirmed = false
                    confirmStartedElapsed = -1L
                }
            }
            confirmedSinceElapsedMs = if (confirmed && confirmStartedElapsed >= 0L) {
                (elapsedRealtimeMs - confirmStartedElapsed).coerceAtLeast(0L)
            } else {
                0L
            }
            return confirmed
        }

        fun reset() {
            recent.clear()
            confirmed = false
            confirmStartedElapsed = -1L
            confirmedSinceElapsedMs = 0L
        }

        fun hint(): String {
            val pos = recent.count { it }
            return if (confirmed) {
                "鼾声确认 ${confirmedSinceElapsedMs / 1000L}s · 窗 $pos/${recent.size}"
            } else {
                "听声中 · 疑似窗 $pos/${recent.size.coerceAtLeast(1)}"
            }
        }
    }
}
