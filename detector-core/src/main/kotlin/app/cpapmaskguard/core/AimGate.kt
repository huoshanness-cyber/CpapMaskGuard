package app.cpapmaskguard.core

/**
 * 上云前的本机对准门。
 * 人脸不是必要条件：全脸面罩经常检不出眼睛。
 * 人体/头部、校准床头、或中央明显有光有纹理，都放行。
 * 拿不准也放行。只拒绝明确的全黑、全白、严重模糊。
 */
object AimGate {
    const val MIN_FACE_WIDTH_FRACTION = 0.12f
    const val MIN_PERSON_AREA_FRACTION = 0.04f
    const val MIN_CENTER_MEAN = 0.06f
    const val MAX_CENTER_MEAN = 0.90f
    const val MIN_CENTER_VARIANCE = 0.0018f
    const val MIN_CENTER_EDGE = 0.012f

    const val BLACK_MEAN = 0.04f
    const val BLACK_VARIANCE = 0.0008f
    const val WHITE_MEAN = 0.93f
    const val WHITE_VARIANCE = 0.002f
    const val BLUR_VARIANCE = 0.0005f
    const val BLUR_EDGE = 0.004f

    const val SOFT_HINT = "镜头可能没对准"

    data class Sample(
        val faceWidthFraction: Float = 0f,
        val personAreaFraction: Float = 0f,
        val sceneMatchesBedside: Boolean = false,
        val centerMean: Float = 0f,
        val centerVariance: Float = 0f,
        val centerEdge: Float = 0f,
    )

    enum class Reason {
        FACE,
        PERSON,
        SCENE,
        BEDSIDE,
        UNCERTAIN,
        BLACK,
        WHITE,
        BLUR,
    }

    data class Verdict(val valid: Boolean, val reason: Reason)

    fun evaluate(sample: Sample): Verdict {
        if (sample.faceWidthFraction >= MIN_FACE_WIDTH_FRACTION) {
            return Verdict(true, Reason.FACE)
        }
        if (sample.personAreaFraction >= MIN_PERSON_AREA_FRACTION) {
            return Verdict(true, Reason.PERSON)
        }
        if (sample.sceneMatchesBedside) {
            return Verdict(true, Reason.SCENE)
        }
        val bedside = sample.centerMean in MIN_CENTER_MEAN..MAX_CENTER_MEAN &&
            sample.centerVariance >= MIN_CENTER_VARIANCE &&
            sample.centerEdge >= MIN_CENTER_EDGE
        if (bedside) {
            return Verdict(true, Reason.BEDSIDE)
        }
        if (sample.centerMean < BLACK_MEAN && sample.centerVariance < BLACK_VARIANCE) {
            return Verdict(false, Reason.BLACK)
        }
        if (sample.centerMean > WHITE_MEAN && sample.centerVariance < WHITE_VARIANCE) {
            return Verdict(false, Reason.WHITE)
        }
        if (sample.centerVariance < BLUR_VARIANCE && sample.centerEdge <= BLUR_EDGE) {
            return Verdict(false, Reason.BLUR)
        }
        return Verdict(true, Reason.UNCERTAIN)
    }
}
