package app.cpapmaskguard.detector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import app.cpapmaskguard.core.FaceBox
import kotlin.math.max

/**
 * 睡觉监测不依赖睁眼。直接按画面中心裁「整脸」和下半脸（口鼻），
 * 不再调用 [android.media.FaceDetector] 找双眼。
 */
object FaceCropper {
    data class CroppedFace(
        val full: Bitmap,
        val lower: Bitmap,
        /** 恒为 false：主路径已停用双眼检测。 */
        val usedFaceDetector: Boolean = false,
        val eyeConfidence: Float = 0f,
    )

    fun crop(source: Bitmap): CroppedFace {
        val small = downscale(source, ImageLimits.ANALYSIS_MAX_SIDE)
        try {
            val boxes = FaceBox.centerFallback(small.width, small.height)
            val full = copyRect(small, boxes.full)
            val lower = copyRect(small, boxes.lower)
            return CroppedFace(full = full, lower = lower)
        } finally {
            if (small !== source) small.recycle()
        }
    }

    private fun downscale(source: Bitmap, maxSide: Int): Bitmap {
        val side = max(source.width, source.height)
        if (side <= maxSide) return source
        val scale = maxSide.toFloat() / side.toFloat()
        val width = (source.width * scale).toInt().coerceAtLeast(2)
        val height = (source.height * scale).toInt().coerceAtLeast(2)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun copyRect(source: Bitmap, rect: FaceBox.RectI): Bitmap {
        val width = rect.width.coerceAtLeast(1)
        val height = rect.height.coerceAtLeast(1)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            source,
            Rect(rect.left, rect.top, rect.right, rect.bottom),
            Rect(0, 0, width, height),
            null,
        )
        return out
    }
}
