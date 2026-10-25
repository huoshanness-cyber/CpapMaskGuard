package app.cpapmaskguard.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import app.cpapmaskguard.settings.SettingsStore
import java.util.concurrent.ConcurrentHashMap

/**
 * 测试版：用系统邮件 Intent 把应用日志发给配置的收件人。
 * 无 SMTP，需用户在邮件 App 里点发送；从 Service 调用时带 NEW_TASK。
 */
object LogMailer {
    private const val MAX_LOG_CHARS = 200 * 1024
    private const val DEBOUNCE_MS = 60_000L

    private val lastAutoByReason = ConcurrentHashMap<String, Long>()

    fun defaultRecipients(): List<String> = listOf(
        "4724771@qq.com",
        "huoshanness@gmail.com",
    )

    fun recipientsOf(context: Context): List<String> =
        SettingsStore(context).reportEmails()

    /**
     * @param force true 时跳过防抖（手动「发送到邮箱」）
     * @return 是否已成功拉起邮件选择器
     */
    fun sendLogEmail(context: Context, reason: String, force: Boolean = false): Boolean {
        val app = context.applicationContext
        if (!force) {
            val now = SystemClock.elapsedRealtime()
            val prev = lastAutoByReason[reason]
            if (prev != null && now - prev < DEBOUNCE_MS) {
                AppLogger.i("LogMailer", "跳过自动发信（60s 内同原因）: $reason")
                return false
            }
            lastAutoByReason[reason] = now
        }

        val recipients = recipientsOf(app).toTypedArray()
        if (recipients.isEmpty()) {
            AppLogger.e("LogMailer", "收件人为空，无法发日志")
            toastSafe(app, "没有收件邮箱")
            return false
        }

        val logBody = try {
            AppLogger.readTail(MAX_LOG_CHARS)
        } catch (error: Throwable) {
            AppLogger.e("LogMailer", "读日志失败", error)
            "（读日志失败：${AppLogger.summarize(error)}）"
        }

        val subject = "[CpapMaskGuard] 日志 ${Build.MODEL} $reason"
        val header = buildString {
            appendLine("原因: $reason")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("品牌/产品: ${Build.BRAND} / ${Build.PRODUCT}")
            appendLine("SDK: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
            appendLine("收件人: ${recipients.joinToString(", ")}")
            appendLine("--- 日志 ---")
        }
        val text = header + logBody

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, recipients)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, "发送日志到邮箱").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            app.startActivity(chooser)
            AppLogger.i("LogMailer", "已打开邮件选择器 reason=$reason to=${recipients.joinToString()}")
            true
        } catch (error: ActivityNotFoundException) {
            AppLogger.e("LogMailer", "没有可用的邮件应用", error)
            toastSafe(app, "没有邮件应用，请先装邮箱或用「分享」")
            false
        } catch (error: Exception) {
            AppLogger.e("LogMailer", "打开邮件失败", error)
            toastSafe(app, "打不开邮件：${AppLogger.summarize(error)}")
            false
        }
    }


    /**
     * 预填健康报告邮件（系统 Intent，用户需点发送；不静默发）。
     * 正文含机型 / Android / App 版本 / 健康分 / 温度 / 延时 / 内存 / 采样时间 / 原因。
     */
    fun sendHealthReport(
        context: Context,
        result: app.cpapmaskguard.health.MonitorHealthScorer.ScoreResult,
        force: Boolean = true,
    ): Boolean {
        val app = context.applicationContext
        if (!force) {
            val now = SystemClock.elapsedRealtime()
            val prev = lastAutoByReason["health"]
            if (prev != null && now - prev < DEBOUNCE_MS) {
                AppLogger.i("LogMailer", "跳过健康报告发信（60s 内）")
                return false
            }
            lastAutoByReason["health"] = now
        }
        val recipients = recipientsOf(app).toTypedArray()
        if (recipients.isEmpty()) {
            toastSafe(app, "没有收件邮箱")
            return false
        }
        val pm = try { app.packageManager.getPackageInfo(app.packageName, 0) } catch (_: Exception) { null }
        val verName = pm?.versionName ?: "?"
        val verCode = if (pm != null) {
            if (android.os.Build.VERSION.SDK_INT >= 28) pm.longVersionCode.toString() else @Suppress("DEPRECATION") pm.versionCode.toString()
        } else "?"
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date(result.sampledAtMs))
        val subject = "[CpapMaskGuard] 健康分 ${result.score} ${Build.MODEL}"
        val body = buildString {
            appendLine("CpapMaskGuard 监测健康报告")
            appendLine("采样时间: $stamp")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("品牌/产品: ${Build.BRAND} / ${Build.PRODUCT}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("App: $verName ($verCode)")
            appendLine("健康分: ${result.score}/100" + (if (result.adviseStop) " （不建议继续使用）" else ""))
            appendLine("原因: ${result.reasonLine}")
            appendLine("电池温度: ${result.batteryTempC?.let { String.format("%.1f", it) + "°C" } ?: "未知"}")
            appendLine("热区温度: ${result.thermalTempC?.let { String.format("%.1f", it) + "°C" } ?: "未知"}")
            appendLine("判定耗时 infer: ${result.inferMs?.let { "${it}ms" } ?: "未知"}")
            appendLine("拍到判定: ${result.captureToJudgeMs?.let { "${it}ms" } ?: "未知"}")
            appendLine("整轮: ${result.roundMs?.let { "${it}ms" } ?: "未知"}")
            appendLine("近1分均拍到判定: ${result.avgCaptureToJudge1mMs?.let { "${it}ms" } ?: "未知"}")
            appendLine("内存 PSS: ${result.pssMb?.let { String.format("%.1f", it) + "MB" } ?: "未知"}")
            appendLine("可用内存: ${result.availMb?.let { String.format("%.1f", it) + "MB" } ?: "未知"}")
            appendLine("连续失败轮次: ${result.consecutiveFailures}")
            appendLine("明细: ${result.detailParts.joinToString(" · ")}")
            appendLine("收件人: ${recipients.joinToString(", ")}")
            appendLine("--- 说明 ---")
            appendLine("本邮件由系统邮件 App 预填，需用户点发送；健康分仅评估提示，不强制停止监测。")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, recipients)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, "发送健康报告").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            app.startActivity(chooser)
            AppLogger.i("LogMailer", "已打开健康报告邮件 score=${result.score}")
            true
        } catch (error: ActivityNotFoundException) {
            AppLogger.e("LogMailer", "没有可用的邮件应用", error)
            toastSafe(app, "没有邮件应用，请先装邮箱")
            false
        } catch (error: Exception) {
            AppLogger.e("LogMailer", "打开健康报告邮件失败", error)
            toastSafe(app, "打不开邮件：${AppLogger.summarize(error)}")
            false
        }
    }

    /**
     * 预填烤机报告邮件（系统 Intent，用户需点发送；不静默发）。
     */
    fun sendBurnInReport(
        context: Context,
        report: app.cpapmaskguard.burnin.BurnInReport,
    ): Boolean {
        val app = context.applicationContext
        val recipients = recipientsOf(app).toTypedArray()
        if (recipients.isEmpty()) {
            toastSafe(app, "没有收件邮箱")
            return false
        }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
        val dateShort = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
            .format(java.util.Date(report.endedAtMs))
        val subject = "CpapMaskGuard 烤机报告 · ${report.model} · $dateShort" +
            (if (report.cancelled) "（未完成）" else "")
        fun f1(v: Float?): String = v?.let { String.format("%.1f", it) } ?: "未知"
        fun li(v: Long?): String = v?.let { "${it}ms" } ?: "未知"
        fun si(v: Int?): String = v?.toString() ?: "未知"
        val plannedMin = (report.plannedDurationMs / 60_000L).toInt()
        val body = buildString {
            appendLine("CpapMaskGuard 使用前烤机报告")
            appendLine("状态: " + when {
                report.cancelled -> "已取消（不完整）"
                report.errorHint != null -> "异常结束"
                else -> "完成"
            })
            appendLine("开始: ${fmt.format(java.util.Date(report.startedAtMs))}")
            appendLine("结束: ${fmt.format(java.util.Date(report.endedAtMs))}")
            appendLine("计划时长: ${plannedMin} 分钟")
            appendLine("采样点数: ${report.sampleCount}")
            appendLine("--- 设备 ---")
            appendLine("厂商: ${report.manufacturer}")
            appendLine("机型: ${report.model}")
            appendLine("DEVICE: ${report.device}")
            appendLine("品牌: ${report.brand}")
            appendLine("Android: ${report.androidRelease} (SDK ${report.sdkInt})")
            appendLine("App: ${report.appVersionName} (${report.appVersionCode})")
            appendLine("--- 监测健康分 ---")
            appendLine("起始分: ${si(report.scoreStart)}")
            appendLine("结束分: ${si(report.scoreEnd)}")
            appendLine("最低分: ${si(report.scoreMin)}")
            appendLine("曾低于60: ${if (report.everBelow60) "是" else "否"}")
            appendLine("--- 电池温度 °C ---")
            appendLine("min/avg/max: ${f1(report.tempMinC)} / ${f1(report.tempAvgC)} / ${f1(report.tempMaxC)}")
            appendLine("--- 拍到判定延时 ---")
            appendLine("min/avg/max: ${li(report.latencyMinMs)} / ${li(report.latencyAvgMs)} / ${li(report.latencyMaxMs)}")
            appendLine("--- 内存 PSS MB ---")
            appendLine("min/avg/max: ${f1(report.pssMinMb)} / ${f1(report.pssAvgMb)} / ${f1(report.pssMaxMb)}")
            appendLine("结束时可用内存: ${f1(report.availEndMb)} MB")
            appendLine("--- 烤机参数 ---")
            appendLine("补光模式: ${report.fillLightMode}")
            appendLine("抓拍间隔: ${report.intervalSec} 秒")
            appendLine("分辨率: ${report.captureWidth}×${report.captureHeight}")
            appendLine("听鼾: 关 · 警报: 关 · 预览: 关")
            if (report.errorHint != null) {
                appendLine("错误提示: ${report.errorHint}")
            }
            appendLine("收件人: ${recipients.joinToString(", ")}")
            appendLine("--- 说明 ---")
            appendLine("本邮件由系统邮件 App 预填，需用户点发送。烤机为使用前第一步，健康分仅提示不强制停用。")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, recipients)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, "发送烤机报告").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            app.startActivity(chooser)
            AppLogger.i("LogMailer", "已打开烤机报告邮件 samples=${report.sampleCount} cancelled=${report.cancelled}")
            true
        } catch (error: ActivityNotFoundException) {
            AppLogger.e("LogMailer", "没有可用的邮件应用", error)
            toastSafe(app, "没有邮件应用，请先装邮箱")
            false
        } catch (error: Exception) {
            AppLogger.e("LogMailer", "打开烤机报告邮件失败", error)
            toastSafe(app, "打不开邮件：${AppLogger.summarize(error)}")
            false
        }
    }

    private fun toastSafe(context: Context, msg: String) {
        try {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
            // Service 线程偶发失败，已写日志
        }
    }
}
