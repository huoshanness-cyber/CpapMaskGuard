package app.cpapmaskguard.detector

import android.graphics.Bitmap
import android.media.FaceDetector
import kotlin.math.max

/**
 * 本机对准材料：旧版双眼人脸检测，加上画面中央的亮度 / 纹理。
 * 人脸检测在闭眼时经常失败，所以只作为「明显有脸」的快捷通过；
 * 枕头区域交给 [app.cpapmaskguard.core.AimGate] 的纹理门和 TFLite 场景比对。
 */
object LocalAimProbe {
    data class Center(
        val mean: Float,
        val variance: Float,
        val edge: Float,
        val signature: List<Float> = emptyList(),
    )

    fun faceWidthFraction(bitmap: Bitmap): Float {
        val prepared = prepareFaceBitmap(bitmap) ?: return 0f
        return try {
            val faces = arrayOfNulls<FaceDetector.Face>(1)
            val found = FaceDetector(prepared.width, prepared.height, 1).findFaces(prepared, faces)
            if (found <= 0) return 0f
            val face = faces[0] ?: return 0f
            if (face.confidence() < 0.3f) return 0f
            (face.eyesDistance() * 2.5f) / prepared.width.toFloat()
        } catch (_: Throwable) {
            0f
        } finally {
            if (prepared !== bitmap && !prepared.isRecycled) prepared.recycle()
        }
    }

    fun center(bitmap: Bitmap): Center {
        val side = 64
        val scaled = Bitmap.createScaledBitmap(bitmap, side, side, true)
        return try {
            val start = side / 4
            val end = side - start
            var sum = 0.0
            var sumSq = 0.0
            var edgeSum = 0.0
            var n = 0
            var edges = 0
            var y = start
            while (y < end) {
                var previous = luma(scaled.getPixel(start, y))
                var x = start
                while (x < end) {
                    val value = luma(scaled.getPixel(x, y))
                    sum += value
                    sumSq += value * value
                    n += 1
                    if (x > start) {
                        edgeSum += kotlin.math.abs(value - previous)
                        edges += 1
                    }
                    previous = value
                    x += 1
                }
                y += 1
            }
            if (n == 0) return Center(0f, 0f, 0f)
            val mean = sum / n
            val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
            val edge = if (edges == 0) 0.0 else edgeSum / edges
            Center(mean.toFloat(), variance.toFloat(), edge.toFloat(), signature(scaled))
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        }
    }

    private fun prepareFaceBitmap(bitmap: Bitmap): Bitmap? {
        if (bitmap.width < 2 || bitmap.height < 2) return null
        val maxSide = 200
        var width = bitmap.width
        var height = bitmap.height
        val longest = max(width, height)
        if (longest > maxSide) {
            val scale = maxSide.toFloat() / longest.toFloat()
            width = (width * scale).toInt()
            height = (height * scale).toInt()
        }
        if (width % 2 != 0) width -= 1
        width = width.coerceAtLeast(2)
        height = height.coerceAtLeast(2)
        val scaled = if (width == bitmap.width && height == bitmap.height) bitmap else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        if (scaled.config == Bitmap.Config.RGB_565) return scaled
        val rgb = scaled.copy(Bitmap.Config.RGB_565, false)
        if (scaled !== bitmap && scaled !== rgb && !scaled.isRecycled) scaled.recycle()
        return rgb
    }

    private fun signature(scaled: Bitmap): List<Float> {
        val cells = 4
        val cell = scaled.width / cells
        if (cell <= 0) return emptyList()
        val sums = FloatArray(cells * cells)
        val counts = IntArray(cells * cells)
        var y = 0
        while (y < scaled.height) {
            var x = 0
            while (x < scaled.width) {
                val index = (y / cell).coerceAtMost(cells - 1) * cells + (x / cell).coerceAtMost(cells - 1)
                sums[index] += luma(scaled.getPixel(x, y)).toFloat()
                counts[index] += 1
                x += 1
            }
            y += 1
        }
        return List(cells * cells) { index ->
            if (counts[index] == 0) 0f else sums[index] / counts[index]
        }
    }

    private fun luma(color: Int): Double {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    }
}
