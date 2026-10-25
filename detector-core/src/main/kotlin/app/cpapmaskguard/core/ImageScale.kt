package app.cpapmaskguard.core

object ImageScale {
    /**
     * 给 BitmapFactory.inSampleSize 用。返回 2 的幂，让长边不会远大于 [maxSide]。
     * 老机器先缩小再进内存，避免一张 720p 以上的照片就把 3GB 内存顶满。
     */
    fun inSampleSize(width: Int, height: Int, maxSide: Int): Int {
        require(maxSide > 0)
        val side = maxOf(width, height)
        if (side <= 0) return 1
        var sample = 1
        while (side / (sample * 2) >= maxSide) {
            sample *= 2
        }
        return sample
    }
}
