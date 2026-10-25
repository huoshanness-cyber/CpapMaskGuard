package app.cpapmaskguard.core

/**
 * 脸部裁剪矩形。睡觉监测不依赖睁眼；默认用画面中心 + 下半脸（口鼻）。
 * [fromEyes] 仅保留给旧测试 / fallback，主路径不应再调用。
 */
object FaceBox {
    /** @deprecated 主路径不再用双眼估脸。 */
    const val FACE_WIDTH_PER_EYE = 2.5f

    /** @deprecated 主路径不再用双眼估脸。 */
    const val FACE_HEIGHT_PER_EYE = 3.2f

    /** @deprecated 主路径不再用双眼估脸。 */
    const val EYES_FROM_TOP = 0.38f

    /**
     * 口鼻区域从整脸高度的这个比例开始，直到下巴。
     * 鼻罩和口鼻罩主要落在这一块。
     */
    const val LOWER_FACE_TOP = 0.40f

    /** @deprecated 主路径不再用瞳距。 */
    const val MIN_EYE_DISTANCE_PX = 12f

    const val MIN_CROP_PX = 24

    data class RectI(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top

        fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
    }

    data class Boxes(val full: RectI, val lower: RectI, val fromEyes: Boolean)

    /** 睡觉场景：取画面中心偏上的区域作为「脸」，再切下半脸口鼻。 */
    fun centerFallback(imageWidth: Int, imageHeight: Int): Boxes {
        val width = imageWidth.coerceAtLeast(2)
        val height = imageHeight.coerceAtLeast(2)
        val cropW = width * 0.70f
        val cropH = height * 0.75f
        val left = (width - cropW) / 2f
        val top = (height - cropH) / 2f
        val full = clamp(left, top, left + cropW, top + cropH, width, height)
        return Boxes(full, lowerOf(full), fromEyes = false)
    }

    /** @deprecated 保留给单元测试；应用主路径请用 [centerFallback]。 */
    fun fromEyes(
        eyeX: Float,
        eyeY: Float,
        eyeDistance: Float,
        imageWidth: Int,
        imageHeight: Int,
    ): Boxes {
        if (imageWidth < 2 || imageHeight < 2 || eyeDistance < MIN_EYE_DISTANCE_PX) {
            return centerFallback(imageWidth.coerceAtLeast(2), imageHeight.coerceAtLeast(2))
        }
        val faceW = eyeDistance * FACE_WIDTH_PER_EYE
        val faceH = eyeDistance * FACE_HEIGHT_PER_EYE
        val top = eyeY - faceH * EYES_FROM_TOP
        val left = eyeX - faceW / 2f
        val full = clamp(left, top, left + faceW, top + faceH, imageWidth, imageHeight)
        if (full.width < MIN_CROP_PX || full.height < MIN_CROP_PX) {
            return centerFallback(imageWidth, imageHeight)
        }
        return Boxes(full, lowerOf(full), fromEyes = true)
    }

    private fun lowerOf(full: RectI): RectI {
        val suggested = full.top + (full.height * LOWER_FACE_TOP).toInt()
        val lowerTop = if (suggested >= full.bottom - 1) full.top + full.height / 2 else suggested
        return RectI(full.left, lowerTop.coerceIn(full.top, full.bottom - 1), full.right, full.bottom)
    }

    private fun clamp(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        imageWidth: Int,
        imageHeight: Int,
    ): RectI {
        val l = left.toInt().coerceIn(0, imageWidth - 1)
        val t = top.toInt().coerceIn(0, imageHeight - 1)
        val r = right.toInt().coerceIn(l + 1, imageWidth)
        val b = bottom.toInt().coerceIn(t + 1, imageHeight)
        return RectI(l, t, r, b)
    }
}
