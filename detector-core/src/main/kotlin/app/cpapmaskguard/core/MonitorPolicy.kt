package app.cpapmaskguard.core

data class FrameSize(val width: Int, val height: Int)

/**
 * 抓拍节奏和分辨率。低端模式给 3GB 左右的老红米用：
 * 间隔放宽到 10–15 秒，分辨率降到 640×480，减轻相机和内存压力。
 */
object MonitorPolicy {
    /** 物理内存低于 4GiB 时，首次打开默认勾上低端模式。4GB 机型常会报得比 4 少。 */
    const val LOW_END_RAM_BYTES: Long = 4L * 1024 * 1024 * 1024

    fun defaultLowEnd(totalRamBytes: Long): Boolean = totalRamBytes in 1 until LOW_END_RAM_BYTES

    fun defaultInterval(lowEnd: Boolean): Int = if (lowEnd) 12 else 8

    fun intervalRange(lowEnd: Boolean): IntRange = if (lowEnd) 10..15 else 5..10

    fun clampInterval(lowEnd: Boolean, seconds: Int): Int =
        seconds.coerceIn(intervalRange(lowEnd).first, intervalRange(lowEnd).last)

    fun defaultResolution(lowEnd: Boolean): FrameSize =
        if (lowEnd) FrameSize(640, 480) else FrameSize(1280, 720)

    /** 界面只提供两档，避免老机器上填一个奇怪的尺寸。 */
    fun sanitizeResolution(width: Int, height: Int): FrameSize {
        return if (width >= 1000 || height >= 700) {
            FrameSize(1280, 720)
        } else {
            FrameSize(640, 480)
        }
    }
}
