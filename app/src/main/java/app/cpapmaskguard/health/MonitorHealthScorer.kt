package app.cpapmaskguard.health

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.SystemClock
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 监测健康分（0–100）：电池温度 / 判定延时 / 内存与稳定性加权合成。
 *
 * 打分公式（写死，可微调但须明确）：
 * - 总分 = clamp(0..100, 0.35*温度分 + 0.35*延时分 + 0.30*稳定分)
 * - 温度分（电池 °C）：≤38 → 100；38–42 线性降到 40；42–45 线性降到 10；≥45 → 0
 *   硬红线：电池 ≥48°C 时总分封顶 59（仅提示，不强制停监测）
 * - 延时分（拍到判定 ms）：≤800 → 100；800–3000 线性降到 30；3000–5000 线性降到 5；≥5000 → 0
 *   若整轮经常 >10s，从延时分再扣最多 25
 * - 稳定分：内存分与失败轮次合成
 *   内存：App PSS / 设备可用内存；占比 ≤8% → 100；8–25% 线性降到 40；≥25% → 15
 *   连续失败：0→不扣；每多 1 次扣 12，最多扣 60
 * - 读不到的项给中性分 70，并在原因里标「未知」
 *
 * 注意：健康分只用于界面评估与邮件报告，不拦截「开始监测」，也不强制停止监测。
 */
object MonitorHealthScorer {
    data class LatencySample(
        val epochElapsedMs: Long,
        val inferMs: Long,
        val captureToJudgeMs: Long,
        val roundMs: Long,
        val failed: Boolean,
    )

    data class ScoreResult(
        val score: Int,
        val batteryTempC: Float?,
        val thermalTempC: Float?,
        val inferMs: Long?,
        val captureToJudgeMs: Long?,
        val roundMs: Long?,
        val avgCaptureToJudge1mMs: Long?,
        val pssMb: Float?,
        val availMb: Float?,
        val consecutiveFailures: Int,
        val reasonLine: String,
        val detailParts: List<String>,
        val hardTempCap: Boolean,
        val sampledAtMs: Long,
    ) {
        val adviseStop: Boolean get() = score < 60
    }

    private val lock = Any()
    private val recent = ArrayDeque<LatencySample>()
    private val consecutiveFailures = AtomicInteger(0)
    private val lastInferMs = AtomicLong(-1L)
    private val lastCaptureToJudgeMs = AtomicLong(-1L)
    private val lastRoundMs = AtomicLong(-1L)

    fun noteRoundSuccess(inferMs: Long, captureToJudgeMs: Long, roundMs: Long) {
        consecutiveFailures.set(0)
        lastInferMs.set(inferMs)
        lastCaptureToJudgeMs.set(captureToJudgeMs)
        lastRoundMs.set(roundMs)
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            recent.addFirst(
                LatencySample(now, inferMs, captureToJudgeMs, roundMs, failed = false),
            )
            trim(now)
        }
    }

    fun noteRoundFailure() {
        consecutiveFailures.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            recent.addFirst(
                LatencySample(now, -1L, -1L, -1L, failed = true),
            )
            trim(now)
        }
    }

    fun resetSession() {
        consecutiveFailures.set(0)
        lastInferMs.set(-1L)
        lastCaptureToJudgeMs.set(-1L)
        lastRoundMs.set(-1L)
        synchronized(lock) { recent.clear() }
    }

    fun score(context: Context): ScoreResult {
        val batteryTemp = readBatteryTempC(context)
        val thermalTemp = readThermalTempC()
        val (pssMb, availMb) = readMemoryMb(context)
        val failCount = consecutiveFailures.get()
        val infer = lastInferMs.get().takeIf { it >= 0 }
        val c2j = lastCaptureToJudgeMs.get().takeIf { it >= 0 }
        val round = lastRoundMs.get().takeIf { it >= 0 }
        val avg1m = avgCaptureToJudge1m()

        val tempForScore = batteryTemp ?: thermalTemp
        val tempScore = scoreTemperature(tempForScore)
        val latencyScore = scoreLatency(c2j, round, avg1m)
        val stabilityScore = scoreStability(pssMb, availMb, failCount)

        var total = (0.35 * tempScore + 0.35 * latencyScore + 0.30 * stabilityScore).roundToInt()
        total = total.coerceIn(0, 100)
        val hardCap = (batteryTemp != null && batteryTemp >= 48f) ||
            (thermalTemp != null && thermalTemp >= 48f)
        if (hardCap) total = min(total, 59)

        val parts = mutableListOf<String>()
        if (batteryTemp != null) {
            parts += "电池 ${fmt1(batteryTemp)}°C"
        } else {
            parts += "电池温度未知"
        }
        if (thermalTemp != null) {
            parts += "热区 ${fmt1(thermalTemp)}°C"
        }
        when {
            c2j != null -> parts += "判定 ${c2j}ms"
            else -> parts += "判定延时未知"
        }
        if (avg1m != null) parts += "近1分均 ${avg1m}ms"
        when {
            pssMb != null && availMb != null -> parts += "内存 ${fmt1(pssMb)}/${fmt1(availMb)}MB"
            pssMb != null -> parts += "内存 PSS ${fmt1(pssMb)}MB"
            else -> parts += "内存未知"
        }
        if (failCount > 0) parts += "连续失败 $failCount"

        val reason = when {
            hardCap -> "设备过热（≥48°C）· ${parts.take(3).joinToString(" · ")}"
            total < 60 -> "不建议继续使用 · ${parts.take(3).joinToString(" · ")}"
            total < 80 -> parts.take(3).joinToString(" · ")
            else -> parts.take(3).joinToString(" · ")
        }

        return ScoreResult(
            score = total,
            batteryTempC = batteryTemp,
            thermalTempC = thermalTemp,
            inferMs = infer,
            captureToJudgeMs = c2j,
            roundMs = round,
            avgCaptureToJudge1mMs = avg1m,
            pssMb = pssMb,
            availMb = availMb,
            consecutiveFailures = failCount,
            reasonLine = reason,
            detailParts = parts,
            hardTempCap = hardCap,
            sampledAtMs = System.currentTimeMillis(),
        )
    }

    fun logLine(result: ScoreResult): String {
        val temp = result.batteryTempC?.let { fmt1(it) } ?: "?"
        val judge = result.captureToJudgeMs?.toString() ?: "?"
        val mem = when {
            result.pssMb != null && result.availMb != null ->
                "${fmt1(result.pssMb)}/${fmt1(result.availMb)}MB"
            result.pssMb != null -> "${fmt1(result.pssMb)}MB"
            else -> "?"
        }
        return "健康分 ${result.score} 温=$temp 判定=${judge}ms 内存=$mem 原因=${result.reasonLine}"
    }

    private fun trim(now: Long) {
        while (recent.size > 120) recent.removeLast()
        val cutoff = now - 60_000L
        while (recent.isNotEmpty() && recent.last().epochElapsedMs < cutoff && recent.size > 8) {
            recent.removeLast()
        }
    }

    private fun avgCaptureToJudge1m(): Long? {
        val now = SystemClock.elapsedRealtime()
        val samples = synchronized(lock) {
            recent.filter { !it.failed && it.captureToJudgeMs >= 0 && now - it.epochElapsedMs <= 60_000L }
        }
        if (samples.isEmpty()) return null
        return samples.map { it.captureToJudgeMs }.average().roundToInt().toLong()
    }

    private fun scoreTemperature(tempC: Float?): Float {
        if (tempC == null) return 70f
        return when {
            tempC <= 38f -> 100f
            tempC <= 42f -> lerp(tempC, 38f, 42f, 100f, 40f)
            tempC <= 45f -> lerp(tempC, 42f, 45f, 40f, 10f)
            else -> 0f
        }
    }

    private fun scoreLatency(captureToJudgeMs: Long?, roundMs: Long?, avg1m: Long?): Float {
        val ms = captureToJudgeMs ?: avg1m
        var base = if (ms == null) {
            70f
        } else {
            when {
                ms <= 800L -> 100f
                ms <= 3000L -> lerp(ms.toFloat(), 800f, 3000f, 100f, 30f)
                ms <= 5000L -> lerp(ms.toFloat(), 3000f, 5000f, 30f, 5f)
                else -> 0f
            }
        }
        val slowRound = roundMs != null && roundMs > 10_000L
        val slowAvg = avg1m != null && avg1m > 4_000L
        if (slowRound || slowAvg) {
            base = max(0f, base - 25f)
        }
        return base
    }

    private fun scoreStability(pssMb: Float?, availMb: Float?, failCount: Int): Float {
        val memScore = when {
            pssMb == null || availMb == null || availMb <= 0f -> 70f
            else -> {
                val ratio = (pssMb / availMb) * 100f
                when {
                    ratio <= 8f -> 100f
                    ratio <= 25f -> lerp(ratio, 8f, 25f, 100f, 40f)
                    else -> 15f
                }
            }
        }
        val failPenalty = min(60f, failCount * 12f)
        return max(0f, memScore - failPenalty)
    }

    private fun readBatteryTempC(context: Context): Float? {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return null
            val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tenths == Int.MIN_VALUE) null else tenths / 10f
        } catch (_: Exception) {
            null
        }
    }

    /** 尝试读 /sys/class/thermal；无 root 多数失败则返回 null。 */
    private fun readThermalTempC(): Float? {
        return try {
            val root = File("/sys/class/thermal")
            if (!root.isDirectory) return null
            var best: Float? = null
            root.listFiles()?.forEach { zone ->
                val typeFile = File(zone, "type")
                val tempFile = File(zone, "temp")
                if (!tempFile.canRead()) return@forEach
                val raw = tempFile.readText().trim().toIntOrNull() ?: return@forEach
                val c = when {
                    raw > 1000 -> raw / 1000f
                    raw > 200 -> raw / 10f
                    else -> raw.toFloat()
                }
                if (c < 10f || c > 95f) return@forEach
                val type = if (typeFile.canRead()) typeFile.readText().trim().lowercase() else ""
                val prefer = type.contains("skin") || type.contains("battery") ||
                    type.contains("ap") || type.contains("cpu")
                if (best == null || prefer) best = c
            }
            best
        } catch (_: Exception) {
            null
        }
    }

    private fun readMemoryMb(context: Context): Pair<Float?, Float?> {
        return try {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            val pssMb = info.totalPss / 1024f
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            val availMb = if (am != null) mi.availMem / (1024f * 1024f) else null
            pssMb to availMb
        } catch (_: Exception) {
            null to null
        }
    }

    private fun lerp(x: Float, x0: Float, x1: Float, y0: Float, y1: Float): Float {
        if (x1 <= x0) return y0
        val t = ((x - x0) / (x1 - x0)).coerceIn(0f, 1f)
        return y0 + (y1 - y0) * t
    }

    private fun fmt1(v: Float): String = String.format("%.1f", v)
}