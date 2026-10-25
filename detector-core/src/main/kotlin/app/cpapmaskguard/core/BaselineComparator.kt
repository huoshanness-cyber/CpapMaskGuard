package app.cpapmaskguard.core
import kotlin.math.abs
import kotlin.math.min

enum class FrameVote {
    /** 这一帧更像校准里「戴着面罩」的基准簇。 */
    CLOSER_TO_MASK_ON,

    /** 这一帧更像校准里「没戴面罩」的基准簇。 */
    CLOSER_TO_MASK_OFF,

    /** 画面几乎全黑、明确无人。不报警，并清脱落票。离基准远不等于无人。 */
    NO_PERSON,

    /** 太暗、离两张都远、或两边差不多。不投票。 */
    UNCERTAIN,
}

data class Comparison(
    val vote: FrameVote,
    /** 0 到 1。只表示这一帧有多偏向其中一类基准，不是患病或脱落的概率。 */
    val confidence: Float,
    val distanceToMaskOn: Float,
    val distanceToMaskOff: Float,
    val reason: String,
)

/**
 * 旧路径：把当前这一帧和两张个人灰度基准比。
 * 默认主路径已改为 [EmbeddingClusterClassifier]；本类保留作 fallback。
 */
class BaselineComparator(
    private val maskOn: FaceFeatures,
    private val maskOff: FaceFeatures,
) {
    fun compare(frame: FaceFeatures): Comparison {
        if (frame.full.variance < DetectionConfig.MIN_LUMA_VARIANCE ||
            frame.lower.variance < DetectionConfig.MIN_LUMA_VARIANCE
        ) {
            return Comparison(
                vote = FrameVote.UNCERTAIN,
                confidence = 0f,
                distanceToMaskOn = -1f,
                distanceToMaskOff = -1f,
                reason = "这张太暗或太平，看不清，先不判断",
            )
        }

        val dOn = distance(frame, maskOn)
        val dOff = distance(frame, maskOff)
        val margin = dOn - dOff
        val closer = min(dOn, dOff)
        val confidence = (abs(margin) / (dOn + dOff + 1e-3f)).coerceIn(0f, 1f)
        val vote = when {
            closer > DetectionConfig.MAX_ACCEPT_DISTANCE -> FrameVote.UNCERTAIN
            margin >= DetectionConfig.MIN_MARGIN && confidence >= DetectionConfig.MIN_CONFIDENCE ->
                FrameVote.CLOSER_TO_MASK_OFF
            -margin >= DetectionConfig.MIN_MARGIN && confidence >= DetectionConfig.MIN_CONFIDENCE ->
                FrameVote.CLOSER_TO_MASK_ON
            else -> FrameVote.UNCERTAIN
        }
        val reason = when (vote) {
            FrameVote.CLOSER_TO_MASK_OFF -> "更接近「没戴面罩」那张基准"
            FrameVote.CLOSER_TO_MASK_ON -> "更接近「戴着面罩」那张基准"
            FrameVote.NO_PERSON -> "画面里几乎没有人"
            FrameVote.UNCERTAIN ->
                if (closer > DetectionConfig.MAX_ACCEPT_DISTANCE) {
                    "和两张基准都差得比较远，先不判断"
                } else {
                    "两张基准差不多近，先不判断"
                }
        }
        return Comparison(
            vote = vote,
            confidence = confidence,
            distanceToMaskOn = dOn,
            distanceToMaskOff = dOff,
            reason = reason,
        )
    }

    companion object {
        fun distance(a: FaceFeatures, b: FaceFeatures): Float {
            val dFull = meanAbsolute(a.full.values, b.full.values)
            val dLower = meanAbsolute(a.lower.values, b.lower.values)
            return DetectionConfig.WEIGHT_FULL * dFull + DetectionConfig.WEIGHT_LOWER * dLower
        }

        private fun meanAbsolute(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "特征长度不一致" }
            var sum = 0.0
            for (i in a.indices) sum += abs(a[i] - b[i])
            return (sum / a.size).toFloat()
        }
    }
}
