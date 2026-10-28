package app.cpapmaskguard.core

/**
 * 面罩比对的可调阈值。这些数是起点，不是临床指标。
 * 真机上用 logcat 过滤 `CpapMaskGuard`，看每一帧的距离再改。
 *
 * 默认主路径：MiniMax-M3 云端看图；本机 TFLite embedding / 灰度作断网备用。
 */
object DetectionConfig {
    /**
     * 连续多少帧都「更接近没戴面罩」才响铃。
     * 真机夜测：侧睡仍戴罩时，云端会把单独一帧判成脱落。
     * 默认 2，这一帧不响。抓拍大约 8 秒一张，第二帧仍在大约 10–30 秒的叫醒窗口里。
     */
    const val DEBOUNCE_CONSECUTIVE = 2

    /**
     * 连续多少帧「看不清 / 两边差不多 / 无人」之后，把已经累计的脱落票清掉。
     */
    const val MAX_UNCERTAIN_STREAK = 2

    // ---- 旧灰度比对（fallback）----

    const val MIN_MARGIN = 0.12f
    const val MIN_CONFIDENCE = 0.08f
    const val MAX_ACCEPT_DISTANCE = 1.05f
    const val WEIGHT_LOWER = 0.65f
    const val WEIGHT_FULL = 0.35f
    const val MIN_LUMA_VARIANCE = 0.0015f
    const val MIN_BASELINE_SEPARATION = 0.20f

    // ---- TFLite embedding 比对 ----

    /** 余弦距离 = 1 - cosine_similarity。两侧最近簇至少差这么多才投确定票。 */
    const val EMBED_MIN_MARGIN = 0.04f

    /** 置信度 = margin / (dOn + dOff + eps) 下限。 */
    const val EMBED_MIN_CONFIDENCE = 0.05f

    /**
     * 离得更近的那一类如果仍然远过这个余弦距离，视为与基准差异大（常见于戴罩）。
     * 不再判 NO_PERSON：按最近簇软投票（同等优先戴罩），或 UNCERTAIN；全黑才 NO_PERSON。
     */
    const val EMBED_MAX_ACCEPT_DISTANCE = 0.55f

    /** 两类簇中心（或最近样本）之间余弦距离低于此值时，校准警告「两类太像」。 */
    const val EMBED_MIN_BASELINE_SEPARATION = 0.08f

    /**
     * 上云前：中心裁剪离「戴 / 不戴」任一基准不超过这个余弦距离，
     * 就当作已经对准床头（比正式分类更松，只回答「像不像这张床」）。
     */
    const val AIM_SCENE_MAX_DISTANCE = 0.62f

    /** 整帧平均亮度低于此值且方差也低时，弱启发为无人/全黑。 */
    const val PERSON_MIN_LUMA_MEAN = 0.04f

    const val PERSON_MIN_LUMA_VARIANCE = 0.0008f

    /** 每类基准最少 / 目标张数（多角度）。 */
    const val BASELINE_MIN_PER_KIND = 3
    const val BASELINE_TARGET_PER_KIND = 5

    /**
     * 单帧本机 TFLite 推理软超时（毫秒）。云端已作主路径；本机仅备用。
     */
    const val LOCAL_INFER_SOFT_TIMEOUT_MS = 400L
}
