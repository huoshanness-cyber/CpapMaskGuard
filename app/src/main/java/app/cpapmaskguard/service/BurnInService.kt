package app.cpapmaskguard.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.cpapmaskguard.FillLightActivity
import app.cpapmaskguard.LOG_TAG
import app.cpapmaskguard.burnin.BurnInPhase
import app.cpapmaskguard.burnin.BurnInReport
import app.cpapmaskguard.burnin.BurnInRuntime
import app.cpapmaskguard.capture.CameraTuning
import app.cpapmaskguard.capture.CaptureController
import app.cpapmaskguard.detector.BaselineStore
import app.cpapmaskguard.detector.BitmapDecode
import app.cpapmaskguard.detector.ImageLimits
import app.cpapmaskguard.detector.MaskDetector
import app.cpapmaskguard.health.MonitorHealthScorer
import app.cpapmaskguard.notify.Notifications
import app.cpapmaskguard.settings.FillLightMode
import app.cpapmaskguard.settings.SettingsStore
import app.cpapmaskguard.util.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 使用前烤机：前台服务跑相机抓拍 + 本地 TFLite，不听鼾、不响警报。
 * 默认 [BurnInRuntime.DEFAULT_DURATION_MS]（5 分钟），结束后由主界面拉起烤机报告邮件。
 */
class BurnInService : LifecycleService() {
    private val settingsStore by lazy { SettingsStore(this) }
    private val capture by lazy { CaptureController(this, this) }
    private val detector by lazy { MaskDetector(this, BaselineStore(this)) }
    private var loopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeAcquiredAt = 0L
    private var cancelRequested = false

    override fun onCreate() {
        super.onCreate()
        alive.set(true)
        Notifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        try {
            showForeground("烤机准备中…")
        } catch (error: Exception) {
            AppLogger.e(LOG_TAG, "烤机前台启动失败", error)
            finishWithError("没能保持在前台：${AppLogger.summarize(error)}")
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_CANCEL -> {
                cancelRequested = true
                loopJob?.cancel()
                // 若循环已不在，立刻收尾
                if (loopJob?.isActive != true) {
                    finalizeAndStop(cancelled = true)
                }
            }
            ACTION_START, null -> {
                if (MonitorService.alive.get()) {
                    finishWithError("请先停止监测，再开始烤机。")
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureLoop()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        capture.close()
        detector.close()
        releaseWake()
        alive.set(false)
        super.onDestroy()
    }

    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        cancelRequested = false
        loopJob = lifecycleScope.launch {
            val durationMs = BurnInRuntime.DEFAULT_DURATION_MS
            val startedWall = System.currentTimeMillis()
            val startedElapsed = SystemClock.elapsedRealtime()
            val temps = mutableListOf<Float>()
            val latencies = mutableListOf<Long>()
            val scores = mutableListOf<Int>()
            val pssList = mutableListOf<Float>()
            var sampleCount = 0
            var scoreStart: Int? = null
            var errorHint: String? = null

            MonitorHealthScorer.resetSession()
            try {
                val health0 = MonitorHealthScorer.score(this@BurnInService)
                scoreStart = health0.score
                health0.batteryTempC?.let { temps += it }
                health0.pssMb?.let { pssList += it }
                scores += health0.score
            } catch (_: Exception) {
            }

            BurnInRuntime.update {
                it.copy(
                    phase = BurnInPhase.RUNNING,
                    elapsedMs = 0L,
                    durationMs = durationMs,
                    sampleCount = 0,
                    currentScore = scoreStart,
                    currentTempC = temps.lastOrNull(),
                    currentCaptureToJudgeMs = null,
                    progressLine = progressText(0L, durationMs),
                    detailLine = "正在打开相机做烤机检测…",
                    report = null,
                )
            }

            val prepared = try {
                withContext(Dispatchers.Default) {
                    val store = BaselineStore(this@BurnInService)
                    val skip = settingsStore.settings().calibrationSkipped || !store.isComplete()
                    detector.prepare(skip)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLogger.e(LOG_TAG, "烤机基准准备失败", error)
                errorHint = AppLogger.summarize(error)
                "准备失败，仍尝试抓拍压测"
            }

            try {
                val settings = settingsStore.settings()
                val wantFront = settings.fillLightMode == FillLightMode.FRONT_SCREEN
                val wantTorch = settings.fillLightMode == FillLightMode.REAR_AMBIENT && settings.torchEnabled
                AppLogger.i(
                    LOG_TAG,
                    "烤机绑定相机 mode=${settings.fillLightMode} front=$wantFront torch=$wantTorch · $prepared",
                )
                if (wantFront) {
                    FillLightActivity.launchIfNeeded(this@BurnInService)
                }
                capture.bind(
                    settings.captureWidth,
                    settings.captureHeight,
                    previewView = null,
                    useFrontCamera = wantFront,
                    enableTorch = wantTorch,
                    torchLevel = settings.torchLevel,
                    requirePreferredLens = true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLogger.e(LOG_TAG, "烤机相机打开失败", error)
                finalizeReport(
                    startedWall = startedWall,
                    cancelled = false,
                    durationMs = durationMs,
                    sampleCount = sampleCount,
                    scoreStart = scoreStart,
                    scores = scores,
                    temps = temps,
                    latencies = latencies,
                    pssList = pssList,
                    errorHint = "相机打不开：${AppLogger.summarize(error)}",
                    phase = BurnInPhase.ERROR,
                )
                cleanupAndStop()
                return@launch
            }

            BurnInRuntime.update {
                it.copy(detailLine = "$prepared · 不听鼾、不响铃 · 预览关闭以贴近睡眠")
            }
            ensureWakeForCycle(intervalMs())

            while (isActive && !cancelRequested) {
                val elapsed = SystemClock.elapsedRealtime() - startedElapsed
                if (elapsed >= durationMs) break

                val roundStart = SystemClock.elapsedRealtime()
                val settings = settingsStore.settings()
                try {
                    val wantFront = settings.fillLightMode == FillLightMode.FRONT_SCREEN
                    val wantTorch = settings.fillLightMode == FillLightMode.REAR_AMBIENT && settings.torchEnabled
                    if (!capture.isBound ||
                        capture.boundWidth != settings.captureWidth ||
                        capture.boundHeight != settings.captureHeight ||
                        capture.usingFront != wantFront
                    ) {
                        capture.bind(
                            settings.captureWidth,
                            settings.captureHeight,
                            previewView = null,
                            useFrontCamera = wantFront,
                            enableTorch = wantTorch,
                            torchLevel = settings.torchLevel,
                            requirePreferredLens = true,
                        )
                    } else {
                        capture.setTorch(wantTorch, settings.torchLevel)
                    }
                    capture.setTorch(wantTorch, settings.torchLevel)
                    val file = capture.takePicture()
                    val captureElapsed = SystemClock.elapsedRealtime()
                    capture.setTorch(wantTorch, settings.torchLevel)

                    val decision = withContext(Dispatchers.Default) {
                        detector.onCapturedFile(file, null, preferCloud = false)
                    }
                    var inferMs = decision.inferMs
                    // 无基准时 onCapturedFile 不跑 TFLite：强制双路 embed 贴近真实负载
                    if (inferMs <= 0L) {
                        inferMs = withContext(Dispatchers.Default) {
                            forceEmbedLoad(file)
                        }
                    }
                    val captureToJudgeMs = SystemClock.elapsedRealtime() - captureElapsed
                    val roundMs = SystemClock.elapsedRealtime() - roundStart
                    file.delete()

                    sampleCount += 1
                    MonitorHealthScorer.noteRoundSuccess(inferMs, captureToJudgeMs, roundMs)
                    latencies += captureToJudgeMs

                    val health = try {
                        MonitorHealthScorer.score(this@BurnInService)
                    } catch (_: Exception) {
                        null
                    }
                    if (health != null) {
                        scores += health.score
                        health.batteryTempC?.let { temps += it }
                        health.pssMb?.let { pssList += it }
                    }
                    val nowElapsed = SystemClock.elapsedRealtime() - startedElapsed
                    BurnInRuntime.update {
                        it.copy(
                            phase = BurnInPhase.RUNNING,
                            elapsedMs = nowElapsed.coerceAtMost(durationMs),
                            durationMs = durationMs,
                            sampleCount = sampleCount,
                            currentScore = health?.score,
                            currentTempC = health?.batteryTempC,
                            currentCaptureToJudgeMs = captureToJudgeMs,
                            progressLine = progressText(nowElapsed, durationMs),
                            detailLine = "采样 #$sampleCount · 判定 ${inferMs}ms · 拍到判定 ${captureToJudgeMs}ms" +
                                (health?.let { h -> " · 健康分 ${h.score}" } ?: ""),
                        )
                    }
                    AppLogger.i(
                        LOG_TAG,
                        "烤机 #$sampleCount infer=${inferMs}ms c2j=${captureToJudgeMs}ms " +
                            "round=${roundMs}ms score=${health?.score} temp=${health?.batteryTempC}",
                    )
                    showForeground(progressText(nowElapsed, durationMs))
                } catch (error: TimeoutCancellationException) {
                    MonitorHealthScorer.noteRoundFailure()
                    AppLogger.w(LOG_TAG, "烤机拍照超时")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    MonitorHealthScorer.noteRoundFailure()
                    AppLogger.e(LOG_TAG, "烤机一轮失败", error)
                    try {
                        capture.unbind()
                    } catch (_: Exception) {
                    }
                }

                val cycleMs = intervalMs()
                ensureWakeForCycle(cycleMs)
                val wait = cycleMs - (SystemClock.elapsedRealtime() - roundStart)
                if (wait > 0 && isActive && !cancelRequested) {
                    val remain = durationMs - (SystemClock.elapsedRealtime() - startedElapsed)
                    delay(minOf(wait, remain.coerceAtLeast(0L)))
                }
            }

            val reallyCancelled = cancelRequested
            finalizeReport(
                startedWall = startedWall,
                cancelled = reallyCancelled,
                durationMs = durationMs,
                sampleCount = sampleCount,
                scoreStart = scoreStart,
                scores = scores,
                temps = temps,
                latencies = latencies,
                pssList = pssList,
                errorHint = errorHint,
                phase = if (reallyCancelled) BurnInPhase.CANCELLED else BurnInPhase.COMPLETED,
            )
            cleanupAndStop()
        }
    }

    private fun forceEmbedLoad(file: java.io.File): Long {
        val started = SystemClock.elapsedRealtime()
        var bmp: Bitmap? = null
        return try {
            bmp = BitmapDecode.decode(file, ImageLimits.DECODE_MAX_SIDE)
            // 贴近真实路径：下半脸 + 整帧各 embed 一次
            detector.embedBitmap(bmp)
            detector.embedBitmap(bmp)
            SystemClock.elapsedRealtime() - started
        } catch (error: Exception) {
            AppLogger.w(LOG_TAG, "烤机强制 embed 失败", error)
            SystemClock.elapsedRealtime() - started
        } finally {
            bmp?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun finalizeReport(
        startedWall: Long,
        cancelled: Boolean,
        durationMs: Long,
        sampleCount: Int,
        scoreStart: Int?,
        scores: List<Int>,
        temps: List<Float>,
        latencies: List<Long>,
        pssList: List<Float>,
        errorHint: String?,
        phase: BurnInPhase,
    ) {
        val endedWall = System.currentTimeMillis()
        val settings = settingsStore.settings()
        val pm = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: Exception) {
            null
        }
        val verName = pm?.versionName ?: "?"
        val verCode = if (pm != null) {
            if (Build.VERSION.SDK_INT >= 28) {
                pm.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                pm.versionCode.toString()
            }
        } else {
            "?"
        }
        val scoreEnd = scores.lastOrNull()
        val scoreMin = scores.minOrNull()
        val healthEnd = try {
            MonitorHealthScorer.score(this)
        } catch (_: Exception) {
            null
        }
        val report = BurnInReport(
            startedAtMs = startedWall,
            endedAtMs = endedWall,
            cancelled = cancelled,
            plannedDurationMs = durationMs,
            sampleCount = sampleCount,
            scoreStart = scoreStart,
            scoreEnd = scoreEnd ?: healthEnd?.score,
            scoreMin = scoreMin,
            everBelow60 = scores.any { it < 60 },
            tempMinC = temps.minOrNull(),
            tempAvgC = temps.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            tempMaxC = temps.maxOrNull(),
            latencyMinMs = latencies.minOrNull(),
            latencyAvgMs = latencies.takeIf { it.isNotEmpty() }
                ?.map { it.toDouble() }?.average()?.toLong(),
            latencyMaxMs = latencies.maxOrNull(),
            pssMinMb = pssList.minOrNull(),
            pssAvgMb = pssList.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            pssMaxMb = pssList.maxOrNull(),
            availEndMb = healthEnd?.availMb,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            brand = Build.BRAND,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            appVersionName = verName,
            appVersionCode = verCode,
            fillLightMode = settings.fillLightMode.name,
            intervalSec = settings.intervalSec,
            captureWidth = settings.captureWidth,
            captureHeight = settings.captureHeight,
            errorHint = errorHint,
        )
        val token = System.currentTimeMillis()
        val detail = when (phase) {
            BurnInPhase.CANCELLED ->
                "已取消（采样 $sampleCount 次）。可选择发送不完整报告。"
            BurnInPhase.ERROR ->
                errorHint ?: "烤机失败"
            else ->
                "烤机完成，采样 $sampleCount 次。正在打开邮件…"
        }
        BurnInRuntime.update {
            it.copy(
                phase = phase,
                elapsedMs = if (cancelled) it.elapsedMs else durationMs,
                durationMs = durationMs,
                sampleCount = sampleCount,
                currentScore = report.scoreEnd,
                currentTempC = report.tempMaxC,
                progressLine = when (phase) {
                    BurnInPhase.CANCELLED -> "烤机已取消"
                    BurnInPhase.ERROR -> "烤机失败"
                    else -> "烤机完成"
                },
                detailLine = detail,
                report = report,
                finishToken = token,
            )
        }
        AppLogger.i(
            LOG_TAG,
            "烤机结束 phase=$phase samples=$sampleCount scoreMin=${report.scoreMin} " +
                "tempMax=${report.tempMaxC} latAvg=${report.latencyAvgMs} cancelled=$cancelled",
        )
    }

    private fun finalizeAndStop(cancelled: Boolean) {
        val snap = BurnInRuntime.snapshot.value
        if (snap.phase == BurnInPhase.RUNNING || snap.phase == BurnInPhase.IDLE) {
            finalizeReport(
                startedWall = System.currentTimeMillis() - snap.elapsedMs,
                cancelled = cancelled,
                durationMs = snap.durationMs,
                sampleCount = snap.sampleCount,
                scoreStart = snap.currentScore,
                scores = listOfNotNull(snap.currentScore),
                temps = listOfNotNull(snap.currentTempC),
                latencies = listOfNotNull(snap.currentCaptureToJudgeMs),
                pssList = emptyList(),
                errorHint = null,
                phase = if (cancelled) BurnInPhase.CANCELLED else BurnInPhase.COMPLETED,
            )
        }
        cleanupAndStop()
    }

    private fun finishWithError(message: String) {
        BurnInRuntime.update {
            it.copy(
                phase = BurnInPhase.ERROR,
                progressLine = "烤机失败",
                detailLine = message,
                report = null,
                finishToken = System.currentTimeMillis(),
            )
        }
        AppLogger.e(LOG_TAG, "烤机错误: $message")
    }

    private fun cleanupAndStop() {
        try {
            capture.unbind()
        } catch (_: Exception) {
        }
        releaseWake()
        try {
            ServiceCompat.stopForeground(this, Service.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun intervalMs(): Long {
        // 贴近真实监测间隔；烤机时不走预览加速
        return settingsStore.settings().intervalMs.coerceIn(5_000L, 15_000L)
    }

    private fun progressText(elapsedMs: Long, durationMs: Long): String {
        val doneMin = (elapsedMs / 60_000L).toInt()
        val totalMin = ((durationMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        val sec = ((elapsedMs % 60_000L) / 1000L).toInt()
        return "烤机中 ${doneMin}分${sec}秒 / 共${totalMin}分钟"
    }

    private fun showForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            Notifications.ID_BURN_IN,
            Notifications.burnIn(this, text),
            type,
        )
    }

    private fun ensureWakeForCycle(intervalMs: Long) {
        val power = getSystemService(PowerManager::class.java) ?: return
        if (wakeLock == null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "app.cpapmaskguard:burnin").apply {
                setReferenceCounted(false)
            }
        }
        val hold = (intervalMs + CameraTuning.CAPTURE_TIMEOUT_MS + 15_000L).coerceIn(30_000L, 180_000L)
        val now = SystemClock.elapsedRealtime()
        val held = wakeLock?.isHeld == true
        if (!held || now - wakeAcquiredAt > hold / 2) {
            try {
                if (held) wakeLock?.release()
            } catch (_: Exception) {
            }
            wakeLock?.acquire(hold)
            wakeAcquiredAt = now
        }
    }

    private fun releaseWake() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "app.cpapmaskguard.BURN_IN_START"
        const val ACTION_CANCEL = "app.cpapmaskguard.BURN_IN_CANCEL"

        val alive = AtomicBoolean(false)

        fun send(context: Context, action: String) {
            val intent = Intent(context, BurnInService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
