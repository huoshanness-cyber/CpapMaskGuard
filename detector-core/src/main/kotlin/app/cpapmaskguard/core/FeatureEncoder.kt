package app.cpapmaskguard.core
import kotlin.math.sqrt

/**
 * 把任意大小的灰度图收成固定小图，并做均值方差归一化。
 * 归一化是为了减轻「基准是开灯拍的、监测是关灯拍的」这种整体亮度差。
 * 它消不掉阴影方向和噪点，所以光线差太多时仍然要重拍基准。
 */
object FeatureEncoder {
    const val SIZE = 24

    fun encode(gray: FloatArray, width: Int, height: Int): EncodedRegion {
        require(width > 0 && height > 0 && gray.size == width * height) {
            "灰度图尺寸对不上"
        }
        val resized = resizeNearest(gray, width, height, SIZE, SIZE)
        return EncodedRegion(
            values = zscore(resized),
            variance = variance(resized),
        )
    }

    fun variance(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        var mean = 0.0
        for (v in values) mean += v
        mean /= values.size
        var acc = 0.0
        for (v in values) {
            val d = v - mean
            acc += d * d
        }
        return (acc / values.size).toFloat()
    }

    fun resizeNearest(
        src: FloatArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
    ): FloatArray {
        val out = FloatArray(dstWidth * dstHeight)
        for (y in 0 until dstHeight) {
            val sy = (y.toLong() * srcHeight / dstHeight).toInt().coerceIn(0, srcHeight - 1)
            val srcRow = sy * srcWidth
            val dstRow = y * dstWidth
            for (x in 0 until dstWidth) {
                val sx = (x.toLong() * srcWidth / dstWidth).toInt().coerceIn(0, srcWidth - 1)
                out[dstRow + x] = src[srcRow + sx]
            }
        }
        return out
    }

    private fun zscore(values: FloatArray): FloatArray {
        var mean = 0.0
        for (v in values) mean += v
        mean /= values.size
        var acc = 0.0
        for (v in values) {
            val d = v - mean
            acc += d * d
        }
        val std = sqrt(acc / values.size).coerceAtLeast(1e-3)
        return FloatArray(values.size) { ((values[it] - mean) / std).toFloat() }
    }
}
