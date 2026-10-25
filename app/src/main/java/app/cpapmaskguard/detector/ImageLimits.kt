package app.cpapmaskguard.detector

object ImageLimits {
    /** 先把照片长边缩到这个量级再进内存。 */
    const val DECODE_MAX_SIDE = 960

    /** 裁剪用的长边。TFLite 会再缩到 224。 */
    const val ANALYSIS_MAX_SIDE = 480
}
