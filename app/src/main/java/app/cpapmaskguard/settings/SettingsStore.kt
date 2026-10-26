package app.cpapmaskguard.settings

import android.app.ActivityManager
import android.content.Context
import app.cpapmaskguard.capture.CameraTuning
import app.cpapmaskguard.core.MonitorPolicy

data class MonitorSettings(
    val lowEndMode: Boolean,
    val intervalSec: Int,
    val captureWidth: Int,
    val captureHeight: Int,
    val calibrationSkipped: Boolean,
    val debugSaveCrop: Boolean,
    val monitoringRequested: Boolean,
    /** 补光模式：前摄白屏 / 后摄自备光源 */
    val fillLightMode: FillLightMode,
    /** 后摄模式下可选：是否开手电筒（默认关） */
    val torchEnabled: Boolean,
    /** 0..1 手电筒强度（仅机型支持时有效） */
    val torchLevel: Float,
    /** 前摄白屏模式的窗口亮度 0..1 */
    val screenBrightness: Float,
    /**
     * 测试看过程：默认关。打开后监测会把最近一帧写到 cache/monitor/last.jpg，
     * 主界面显示缩略图；约 5 分钟后自动关掉（见 [SettingsStore.isMonitorPreviewActive]）。
     */
    val showMonitorPreview: Boolean,
) {
    val intervalMs: Long get() = intervalSec * 1000L
    val useFrontCamera: Boolean get() = fillLightMode.useFrontCamera
}

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val totalRamBytes: Long

    init {
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        totalRamBytes = info.totalMem
    }

    fun settings(): MonitorSettings {
        val lowEnd = if (prefs.contains(KEY_LOW_END)) {
            prefs.getBoolean(KEY_LOW_END, true)
        } else {
            MonitorPolicy.defaultLowEnd(totalRamBytes)
        }
        val interval = if (prefs.contains(KEY_INTERVAL)) {
            MonitorPolicy.clampInterval(lowEnd, prefs.getInt(KEY_INTERVAL, MonitorPolicy.defaultInterval(lowEnd)))
        } else {
            MonitorPolicy.defaultInterval(lowEnd)
        }
        val fallback = MonitorPolicy.defaultResolution(lowEnd)
        val size = MonitorPolicy.sanitizeResolution(
            prefs.getInt(KEY_WIDTH, fallback.width),
            prefs.getInt(KEY_HEIGHT, fallback.height),
        )
        return MonitorSettings(
            lowEndMode = lowEnd,
            intervalSec = interval,
            captureWidth = size.width,
            captureHeight = size.height,
            calibrationSkipped = prefs.getBoolean(KEY_SKIP, false),
            debugSaveCrop = prefs.getBoolean(KEY_DEBUG_CROP, false),
            monitoringRequested = prefs.getBoolean(KEY_REQUESTED, false),
            fillLightMode = resolveFillLightMode(),
            torchEnabled = prefs.getBoolean(KEY_TORCH, false),
            torchLevel = prefs.getFloat(KEY_TORCH_LEVEL, CameraTuning.DEFAULT_TORCH_LEVEL).coerceIn(0f, 1f),
            screenBrightness = prefs.getFloat(KEY_SCREEN_BRIGHTNESS, DEFAULT_SCREEN_BRIGHTNESS).coerceIn(0.05f, 1f),
            showMonitorPreview = isMonitorPreviewActive(),
        )
    }

    /**
     * 新键优先；未写过则：曾明确选前摄→前摄白屏，否则首次默认前摄白屏。
     * （旧版默认后置+弱光，不再作为新默认。）
     */
    private fun resolveFillLightMode(): FillLightMode {
        if (prefs.contains(KEY_FILL_MODE)) {
            return FillLightMode.fromStorage(prefs.getString(KEY_FILL_MODE, null))
        }
        // 升级用户：保留旧「前/后摄」选择；全新安装无 KEY_FRONT → 默认前摄白屏
        if (prefs.contains(KEY_FRONT)) {
            return if (prefs.getBoolean(KEY_FRONT, false)) {
                FillLightMode.FRONT_SCREEN
            } else {
                FillLightMode.REAR_AMBIENT
            }
        }
        return FillLightMode.FRONT_SCREEN
    }

    fun setLowEnd(enabled: Boolean) {
        val size = MonitorPolicy.defaultResolution(enabled)
        prefs.edit()
            .putBoolean(KEY_LOW_END, enabled)
            .putInt(KEY_INTERVAL, MonitorPolicy.defaultInterval(enabled))
            .putInt(KEY_WIDTH, size.width)
            .putInt(KEY_HEIGHT, size.height)
            .apply()
    }

    fun setInterval(seconds: Int) {
        val lowEnd = settings().lowEndMode
        prefs.edit().putInt(KEY_INTERVAL, MonitorPolicy.clampInterval(lowEnd, seconds)).apply()
    }

    fun setResolution(width: Int, height: Int) {
        val size = MonitorPolicy.sanitizeResolution(width, height)
        prefs.edit().putInt(KEY_WIDTH, size.width).putInt(KEY_HEIGHT, size.height).apply()
    }

    fun setCalibrationSkipped(skipped: Boolean) {
        prefs.edit().putBoolean(KEY_SKIP, skipped).apply()
    }

    fun setDebugSaveCrop(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_CROP, enabled).apply()
    }

    fun setMonitoringRequested(requested: Boolean) {
        prefs.edit().putBoolean(KEY_REQUESTED, requested).apply()
    }

    fun setFillLightMode(mode: FillLightMode) {
        prefs.edit()
            .putString(KEY_FILL_MODE, mode.name)
            .putBoolean(KEY_FRONT, mode.useFrontCamera)
            .apply()
        // 前摄模式强制关手电筒；后摄模式默认关（用户可再开高级项）
        if (mode == FillLightMode.FRONT_SCREEN) {
            prefs.edit().putBoolean(KEY_TORCH, false).apply()
        }
    }

    /** @deprecated 用 [setFillLightMode]；保留给旧调用兼容 */
    fun setUseFrontCamera(front: Boolean) {
        setFillLightMode(if (front) FillLightMode.FRONT_SCREEN else FillLightMode.REAR_AMBIENT)
    }

    fun setTorchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TORCH, enabled).apply()
    }

    fun setTorchLevel(level: Float) {
        prefs.edit().putFloat(KEY_TORCH_LEVEL, level.coerceIn(0f, 1f)).apply()
    }

    fun setScreenBrightness(level: Float) {
        prefs.edit().putFloat(KEY_SCREEN_BRIGHTNESS, level.coerceIn(0.05f, 1f)).apply()
    }

    fun batteryPromptShown(): Boolean = prefs.getBoolean(KEY_BATTERY_PROMPT, false)

    fun setBatteryPromptShown() {
        prefs.edit().putBoolean(KEY_BATTERY_PROMPT, true).apply()
    }

    fun onboardingDone(): Boolean = prefs.getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingDone() {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }

    fun xiaomiGuideShown(): Boolean = prefs.getBoolean(KEY_XIAOMI_GUIDE, false)

    fun setXiaomiGuideShown() {
        prefs.edit().putBoolean(KEY_XIAOMI_GUIDE, true).apply()
    }

    /** 预览是否仍有效（未过期）。过期时会顺手关掉开关。 */
    fun isMonitorPreviewActive(): Boolean {
        if (!prefs.getBoolean(KEY_SHOW_PREVIEW, false)) return false
        val until = prefs.getLong(KEY_PREVIEW_UNTIL, 0L)
        if (until > 0L && System.currentTimeMillis() >= until) {
            prefs.edit().putBoolean(KEY_SHOW_PREVIEW, false).putLong(KEY_PREVIEW_UNTIL, 0L).commit()
            return false
        }
        return true
    }

    fun previewRemainingMs(): Long {
        if (!prefs.getBoolean(KEY_SHOW_PREVIEW, false)) return 0L
        val until = prefs.getLong(KEY_PREVIEW_UNTIL, 0L)
        if (until <= 0L) return 0L
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** 打开测试预览，默认约 5 分钟后自动关。 */
    fun enableMonitorPreview(durationMs: Long = PREVIEW_DURATION_MS) {
        // commit：同进程 Service 立刻读到，避免 apply 异步导致漏写 last.jpg
        prefs.edit()
            .putBoolean(KEY_SHOW_PREVIEW, true)
            .putLong(KEY_PREVIEW_UNTIL, System.currentTimeMillis() + durationMs)
            .commit()
    }

    fun disableMonitorPreview() {
        prefs.edit().putBoolean(KEY_SHOW_PREVIEW, false).putLong(KEY_PREVIEW_UNTIL, 0L).commit()
    }

    fun setShowMonitorPreview(enabled: Boolean) {
        if (enabled) enableMonitorPreview() else disableMonitorPreview()
    }

    fun reportEmails(): List<String> {
        val raw = prefs.getString(KEY_REPORT_EMAILS, null)?.trim().orEmpty()
        if (raw.isEmpty()) return DEFAULT_REPORT_EMAILS
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { DEFAULT_REPORT_EMAILS }
    }

    fun reportEmailsCsv(): String = reportEmails().joinToString(", ")

    fun setReportEmails(csv: String) {
        val cleaned = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val value = if (cleaned.isEmpty()) DEFAULT_REPORT_EMAILS.joinToString(",") else cleaned.joinToString(",")
        prefs.edit().putString(KEY_REPORT_EMAILS, value).apply()
    }

    /** 图片捐赠。默认关。没有上传地址时即使打开也只留在本机，不会发出去。 */
    fun imageDonationEnabled(): Boolean = prefs.getBoolean(KEY_IMAGE_DONATION, false)

    fun setImageDonationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IMAGE_DONATION, enabled).apply()
    }

    private companion object {
        const val PREFS = "cpap_mask_guard"
        const val KEY_LOW_END = "low_end"
        const val KEY_INTERVAL = "interval_sec"
        const val KEY_WIDTH = "capture_width"
        const val KEY_HEIGHT = "capture_height"
        const val KEY_SKIP = "calibration_skipped"
        const val KEY_DEBUG_CROP = "debug_save_crop"
        const val KEY_REQUESTED = "monitoring_requested"
        const val KEY_FRONT = "use_front_camera"
        const val KEY_FILL_MODE = "fill_light_mode"
        const val KEY_TORCH = "torch_enabled"
        const val KEY_TORCH_LEVEL = "torch_level"
        const val KEY_SCREEN_BRIGHTNESS = "screen_brightness"
        const val KEY_BATTERY_PROMPT = "battery_prompt_shown"
        const val KEY_ONBOARDING_DONE = "onboarding_done_v037"
        const val KEY_XIAOMI_GUIDE = "xiaomi_guide_shown"
        const val KEY_REPORT_EMAILS = "report_emails"
        const val KEY_SHOW_PREVIEW = "show_monitor_preview"
        const val KEY_PREVIEW_UNTIL = "preview_until_epoch_ms"
        const val KEY_IMAGE_DONATION = "image_donation_enabled"
        const val PREVIEW_DURATION_MS = 5L * 60L * 1000L
        const val DEFAULT_SCREEN_BRIGHTNESS = 0.85f

        val DEFAULT_REPORT_EMAILS = listOf(
            "4724771@qq.com",
            "huoshanness@gmail.com",
        )
    }
}
