package app.cpapmaskguard.detector

import android.graphics.Bitmap
import app.cpapmaskguard.core.FaceFeatures
import app.cpapmaskguard.core.FeatureEncoder

data class PipelineResult(
    val features: FaceFeatures,
    val usedFaceDetector: Boolean,
    val eyeConfidence: Float,
    val fullCrop: Bitmap,
    val lowerCrop: Bitmap,
) {
    fun recycleCrops() {
        if (!fullCrop.isRecycled) fullCrop.recycle()
        if (!lowerCrop.isRecycled) lowerCrop.recycle()
    }
}

/** 灰度特征管道：仅作 TFLite 不可用时的 fallback。 */
object FeaturePipeline {
    fun fromBitmap(bitmap: Bitmap): PipelineResult {
        val cropped = FaceCropper.crop(bitmap)
        return try {
            val (fullGray, fullW, fullH) = GrayImages.fromBitmap(cropped.full)
            val (lowerGray, lowerW, lowerH) = GrayImages.fromBitmap(cropped.lower)
            PipelineResult(
                features = FaceFeatures(
                    full = FeatureEncoder.encode(fullGray, fullW, fullH),
                    lower = FeatureEncoder.encode(lowerGray, lowerW, lowerH),
                ),
                usedFaceDetector = false,
                eyeConfidence = 0f,
                fullCrop = cropped.full,
                lowerCrop = cropped.lower,
            )
        } catch (error: Throwable) {
            if (!cropped.full.isRecycled) cropped.full.recycle()
            if (!cropped.lower.isRecycled) cropped.lower.recycle()
            throw error
        }
    }
}
