package app.cpapmaskguard.detector

import android.graphics.Bitmap
import app.cpapmaskguard.core.DetectionConfig

/** 弱启发：整帧太暗且几乎无纹理 → 当作无人/全黑。 */
object LumaStats {
    data class Stats(val mean: Float, val variance: Float) {
        val tooDarkForPerson: Boolean
            get() = mean < DetectionConfig.PERSON_MIN_LUMA_MEAN &&
                variance < DetectionConfig.PERSON_MIN_LUMA_VARIANCE
    }

    fun of(bitmap: Bitmap): Stats {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 1 || h < 1) return Stats(0f, 0f)
        val stepX = (w / 32).coerceAtLeast(1)
        val stepY = (h / 32).coerceAtLeast(1)
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
                sum += luma
                sumSq += luma * luma
                n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0) return Stats(0f, 0f)
        val mean = sum / n
        val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
        return Stats(mean.toFloat(), variance.toFloat())
    }
}
