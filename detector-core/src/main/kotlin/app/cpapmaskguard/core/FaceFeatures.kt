package app.cpapmaskguard.core

/** 一块已经缩放过的灰度区域。[variance] 是 z-score 之前的亮度方差。 */
data class EncodedRegion(
    val values: FloatArray,
    val variance: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedRegion) return false
        return variance == other.variance && values.contentEquals(other.values)
    }

    override fun hashCode(): Int {
        var result = values.contentHashCode()
        result = 31 * result + variance.hashCode()
        return result
    }
}

/**
 * 一帧脸的特征：整脸 + 下半脸（口鼻）。
 * 两块都是固定长度的 z-score 灰度，方便直接算距离。
 */
data class FaceFeatures(
    val full: EncodedRegion,
    val lower: EncodedRegion,
) {
    init {
        require(full.values.isNotEmpty() && full.values.size == lower.values.size) {
            "整脸和下半脸的特征长度必须相同且非空"
        }
    }
}
