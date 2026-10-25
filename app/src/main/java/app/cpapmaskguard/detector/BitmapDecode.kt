package app.cpapmaskguard.detector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.cpapmaskguard.core.ImageScale
import java.io.File

object BitmapDecode {
    fun decode(file: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("照片没有尺寸")
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageScale.inSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options) ?: error("解不开这张照片")
    }
}
