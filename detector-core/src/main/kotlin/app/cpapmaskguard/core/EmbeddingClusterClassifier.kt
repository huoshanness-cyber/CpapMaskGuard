package app.cpapmaskguard.core

import kotlin.math.abs
import kotlin.math.min

/**
 * 本机轻量模型主路径：用多张「戴 / 不戴」基准 embedding 做最近簇分类。
 * 输出 MASK_ON / MASK_OFF / NO_PERSON / UNCERTAIN（映射到 [FrameVote]）。
 *
 * NO_PERSON 仅在画面几乎全黑时使用；戴罩导致 embedding 离基准较远时，
 * 按最近簇投票（同等距离优先戴罩），绝不误报「未检测到人」。
 */
class EmbeddingClusterClassifier(
    private val maskOn: List<FloatArray>,
    private val maskOff: List<FloatArray>,
) {
    init {
        require(maskOn.isNotEmpty() && maskOff.isNotEmpty()) { "两类基准都不能为空" }
    }

    fun compare(embedding: FloatArray, frameTooDark: Boolean = false): Comparison {
        if (frameTooDark) {
            return Comparison(
                vote = FrameVote.NO_PERSON,
                confidence = 0f,
                distanceToMaskOn = -1f,
                distanceToMaskOff = -1f,
                reason = "画面几乎全黑，当作无人，不报警",
            )
        }
        val query = EmbeddingMath.l2Normalize(embedding)
        val dOn = EmbeddingMath.nearestDistance(query, maskOn)
        val dOff = EmbeddingMath.nearestDistance(query, maskOff)
        val closer = min(dOn, dOff)
        val margin = dOn - dOff
        val confidence = (abs(margin) / (dOn + dOff + 1e-3f)).coerceIn(0f, 1f)

        // 离所有基准都较远：不是「无人」，而是场景/裁剪与基准差异大（戴 CPAP 罩尤甚）。
        // 按最近簇软投票；距离并列时优先戴罩，避免「有人戴罩 → 未检测到人」。
        if (closer > DetectionConfig.EMBED_MAX_ACCEPT_DISTANCE) {
            return if (dOn <= dOff) {
                Comparison(
                    vote = FrameVote.CLOSER_TO_MASK_ON,
                    confidence = confidence,
                    distanceToMaskOn = dOn,
                    distanceToMaskOff = dOff,
                    reason = "离基准较远，按更近的戴罩簇（避免误报无人）",
                )
            } else {
                Comparison(
                    vote = FrameVote.UNCERTAIN,
                    confidence = confidence,
                    distanceToMaskOn = dOn,
                    distanceToMaskOff = dOff,
                    reason = "离基准较远且更像未戴，但不够近，先不判无人也不报警",
                )
            }
        }

        val vote = when {
            margin >= DetectionConfig.EMBED_MIN_MARGIN &&
                confidence >= DetectionConfig.EMBED_MIN_CONFIDENCE -> FrameVote.CLOSER_TO_MASK_OFF
            -margin >= DetectionConfig.EMBED_MIN_MARGIN &&
                confidence >= DetectionConfig.EMBED_MIN_CONFIDENCE -> FrameVote.CLOSER_TO_MASK_ON
            else -> FrameVote.UNCERTAIN
        }
        val reason = when (vote) {
            FrameVote.CLOSER_TO_MASK_OFF -> "更接近「没戴面罩」基准簇"
            FrameVote.CLOSER_TO_MASK_ON -> "更接近「戴着面罩」基准簇"
            FrameVote.NO_PERSON -> "无人"
            FrameVote.UNCERTAIN -> "两类差不多近，先不判断"
        }
        return Comparison(vote, confidence, dOn, dOff, reason)
    }

    fun clusterSeparation(): Float {
        val onMean = EmbeddingMath.meanVector(maskOn)
        val offMean = EmbeddingMath.meanVector(maskOff)
        return EmbeddingMath.cosineDistance(onMean, offMean)
    }
}
