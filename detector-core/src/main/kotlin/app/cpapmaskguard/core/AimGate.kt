package app.cpapmaskguard.core

/**
 * 上云前的本机对准门。
 * 脸、人、已校准的床头场景，或画面中央像被照亮的枕头，才算对准。
 * 全黑、过曝、几乎没纹理的画面不算，避免把天花板或盖住的镜头送给云端。
 */
object AimGate {
    const val MIN_FACE_WIDTH_FRACTION = 0.12f
    const val MIN_PERSON_AREA_FRACTION = 0.04f
    const val MIN_CENTER_MEAN = 0.06f
    const val MAX_CENTER_MEAN = 0.90f
    const val MIN_CENTER_VARIANCE = 0.0018f
    const val MIN_CENTER_EDGE = 0.012f

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
        MISSED,
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
        return Verdict(false, Reason.MISSED)
    }
}
