package app.cpapmaskguard.detector

import android.graphics.Bitmap

object GrayImages {
    fun fromBitmap(bitmap: Bitmap): Triple<FloatArray, Int, Int> {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = FloatArray(pixels.size)
        for (index in pixels.indices) {
            val color = pixels[index]
            val red = (color shr 16) and 0xFF
            val green = (color shr 8) and 0xFF
            val blue = color and 0xFF
            gray[index] = (0.299f * red + 0.587f * green + 0.114f * blue) / 255f
        }
        return Triple(gray, width, height)
    }
}
