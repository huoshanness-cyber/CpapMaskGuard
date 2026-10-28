package app.cpapmaskguard.capture

/**
 * 夜间拍照与弱光常亮的可调参数。
 */
object CameraTuning {
    /**
     * 夜景扩展不可用时，额外曝光补偿的档数。
     * 很多手机一档大约 1/3 EV。画面发白、口鼻对比变差时改成 0。
     */
    const val EXPOSURE_COMPENSATION_STEPS = 1

    /** 低端机夜景多帧可能要十几秒，超时就放弃这一张。 */
    const val CAPTURE_TIMEOUT_MS = 25_000L

    const val JPEG_QUALITY = 85

    /** 预览 last.jpg 再压一档，减小主界面解码量；不影响检测用原图。 */
    const val PREVIEW_JPEG_QUALITY = 70

    /**
     * 预览开启时临时抓拍间隔（毫秒）：倒计时 3→2→1→抓拍，整轮约 3 秒。
     * 关掉后恢复设置里的间隔。
     */
    const val PREVIEW_CAPTURE_INTERVAL_MS = 3_000L

    /** 预览倒计时从几秒开始（与 [PREVIEW_CAPTURE_INTERVAL_MS] 对齐）。 */
    const val PREVIEW_COUNTDOWN_SEC = 3

    /** 校准页「先放好手机再拍」的倒计时。 */
    const val CALIBRATION_COUNTDOWN_SEC = 5

    /**
     * 默认手电筒强度（0..1）。预设微弱，尽量不影响睡眠。
     * API 33+ 且机型支持 strength 时生效；否则只能全开/关。
     */
    /** 过夜建议 2%。滑条仍可降到约 1%。只能开关的机器不会被这个值关掉。 */
    const val DEFAULT_TORCH_LEVEL = 0.02f
    const val MIN_TORCH_LEVEL = 0.01f
}
