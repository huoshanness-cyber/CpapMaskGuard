package app.cpapmaskguard.service

import android.app.Service
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.cpapmaskguard.LOG_TAG
import app.cpapmaskguard.alarm.AlarmController
import app.cpapmaskguard.audio.SnoreListener
import app.cpapmaskguard.capture.CaptureController
import app.cpapmaskguard.capture.CameraTuning
import app.cpapmaskguard.core.FrameVote
import app.cpapmaskguard.core.SnoreFusion
import app.cpapmaskguard.detector.BaselineStore
import app.cpapmaskguard.detector.MaskDetector
import app.cpapmaskguard.notify.Notifications
import app.cpapmaskguard.runtime.MonitorRuntime
import app.cpapmaskguard.runtime.RunStatus
import app.cpapmaskguard.settings.FillLightMode
import app.cpapmaskguard.settings.SettingsStore
import app.cpapmaskguard.FillLightActivity
import app.cpapmaskguard.health.MonitorHealthScorer
import app.cpapmaskguard.util.AppLogger
import app.cpapmaskguard.util.LogMailer
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
 * 前台服务：常驻通知、按间隔拍照、听鼾辅助、比对基准、需要时响铃。
 * 用户划掉最近任务时 `stopWithTask=false`，但小米仍可能清掉进程。
 */
class MonitorService : LifecycleService() {
    private val settingsStore by lazy { SettingsStore(this) }
    private val capture by lazy { CaptureController(this, this) }
    private val detector by lazy { MaskDetector(this, BaselineStore(this)) }
    private val alarm by lazy { AlarmController(this) }
    private val snore by lazy { SnoreListener(this) }
    private var loopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeAcquiredAt = 0L
    private var ignoreFramesUntil = 0L
    private var consecutiveFailures = 0
    private var roundCount = 0
    private var audioStarted = false

    override fun onCreate() {
        super.onCreate()
        alive.set(true)
        Notifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        try {
            if (action == ACTION_STOP) {
                showForeground("正在停止")
                stopFromUser()
                return START_NOT_STICKY
            }
            showForeground(MonitorRuntime.snapshot.value.detail)
        } catch (error: Exception) {
            AppLogger.e(LOG_TAG, "前台服务启动失败", error)
            settingsStore.setMonitoringRequested(false)
            alive.set(false)
            publish(RunStatus.ERROR, "没能保持在前台", AppLogger.summarize(error))
            LogMailer.sendLogEmail(this, "前台启动失败")
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_STOP_ALARM -> stopAlarm()
            ACTION_START, null -> {
                if (action == ACTION_START || settingsStore.settings().monitoringRequested) {
                    settingsStore.setMonitoringRequested(true)
                    ensureLoop()
                } else {
                    stopFromUser()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        snore.stop()
        audioStarted = false
        capture.close()
        detector.close()
        alarm.stop()
        Notifications.cancelAlarm(this)
        releaseWake()
        alive.set(false)
        super.onDestroy()
    }

    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        loopJob = lifecycleScope.launch {
            publish(RunStatus.STARTING, "正在确认面罩", "正在打开摄像头，请保持对准枕头。")
            val prepared = try {
                withContext(Dispatchers.Default) {
                    detector.prepare(settingsStore.settings().calibrationSkipped)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLogger.e(LOG_TAG, "基准读失败", error)
                publish(RunStatus.ERROR, "基准读不出来", "请重新拍摄多角度基准。${AppLogger.summarize(error)}")
                return@launch
            }
            try {
                val settings = settingsStore.settings()
                val wantFront = settings.fillLightMode == FillLightMode.FRONT_SCREEN
                val wantTorch = settings.fillLightMode == FillLightMode.REAR_AMBIENT && settings.torchEnabled
                AppLogger.i(
                    LOG_TAG,
                    "监测绑定相机 mode=${settings.fillLightMode} front=$wantFront torch=$wantTorch " +
                        "screenBrightness=${settings.screenBrightness}",
                )
                if (wantFront) {
                    FillLightActivity.launchIfNeeded(this@MonitorService)
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
                val mode = settingsStore.settings().fillLightMode
                AppLogger.e(LOG_TAG, "相机打开失败 mode=$mode", error)
                val head = if (mode == FillLightMode.FRONT_SCREEN) "前摄打不开" else "相机打不开"
                publish(
                    RunStatus.ERROR,
                    head,
                    "${AppLogger.summarize(error)}。请到主界面「查看日志」；可改选「后摄 + 自备光源」或重试。",
                )
                LogMailer.sendLogEmail(this@MonitorService, "相机打开失败 mode=$mode")
                return@launch
            }
            audioStarted = snore.start()
            if (!audioStarted) {
                AppLogger.w(LOG_TAG, "听鼾未启动（无权限或麦克风失败），仅相机监测")
            }
            roundCount = 0
            MonitorHealthScorer.resetSession()
            val modeHint = when (settingsStore.settings().fillLightMode) {
                FillLightMode.FRONT_SCREEN -> "前摄·屏幕补光"
                FillLightMode.REAR_AMBIENT -> "后摄·自备光源"
            }
            publish(
                RunStatus.MONITORING,
                "正在确认面罩",
                if (audioStarted) {
                    "$prepared · $modeHint · 听鼾已开"
                } else {
                    "$prepared · $modeHint · 听鼾未开（仅相机）"
                },
                capture.nightExtension,
            )
            ensureWakeForCycle(effectiveIntervalMs())
            while (isActive && settingsStore.settings().monitoringRequested) {
                val startedAt = SystemClock.elapsedRealtime()
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
                        // 前摄强制关灯；后摄仅高级开关开时点亮
                        capture.setTorch(wantTorch, settings.torchLevel)
                    }
                    // 拍照前再确认一次（与是否开预览无关）
                    capture.setTorch(wantTorch, settings.torchLevel)
                    val file = capture.takePicture()
                    // 抓拍完成时刻：用于界面/日志「拍摄时间」，必须在判定之前记
                    val captureEpoch = System.currentTimeMillis()
                    val captureElapsed = SystemClock.elapsedRealtime()
                    // 拍照后小米常把灯掐掉；立刻重新点亮并 await
                    capture.setTorch(wantTorch, settings.torchLevel)
                    val previewOn = settingsStore.isMonitorPreviewActive()
                    val cropOut = when {
                        previewOn -> File(cacheDir, "monitor/last_crop.jpg").also { it.parentFile?.mkdirs() }
                        settings.debugSaveCrop -> File(cacheDir, "debug/last_crop.jpg")
                        else -> null
                    }
                    val decision = withContext(Dispatchers.Default) {
                        detector.onCapturedFile(file, cropOut)
                    }
                    val captureToJudgeMs = SystemClock.elapsedRealtime() - captureElapsed
                    val fused = SnoreFusion.fuse(
                        cameraShouldAlarm = decision.shouldAlarm,
                        cameraVote = decision.vote,
                        snoreConfirmed = snore.isConfirmed(),
                        snoreConfirmedForMs = snore.confirmedForMs(),
                        cameraSummary = decision.summary,
                    )
                    val resultLabel = previewResultLabel(decision.vote, fused.shouldAlarm)
                    val spent = SystemClock.elapsedRealtime() - startedAt
                    val timeText = formatCaptureClock(captureEpoch)
                    // 界面列表：一眼能看拍摄时间、结果、判定耗时
                    val uiLine =
                        "$timeText  $resultLabel  判定${decision.inferMs}ms  拍到判定${captureToJudgeMs}ms  整轮${spent}ms"
                    if (previewOn) {
                        saveLastFrame(file, captureEpoch)
                    }
                    // 预览开或关都写入，开预览时列表立刻可见
                    MonitorRuntime.appendCaptureLog(uiLine)
                    file.delete()
                    consecutiveFailures = 0
                    roundCount += 1
                    MonitorHealthScorer.noteRoundSuccess(
                        inferMs = decision.inferMs,
                        captureToJudgeMs = captureToJudgeMs,
                        roundMs = spent,
                    )
                    try {
                        val health = MonitorHealthScorer.score(this@MonitorService)
                        AppLogger.i(LOG_TAG, MonitorHealthScorer.logLine(health))
                    } catch (_: Exception) {
                    }
                    AppLogger.i(
                        LOG_TAG,
                        "延时 #$roundCount 拍摄=$timeText 判定=$resultLabel vote=${decision.vote} " +
                            "infer=${decision.inferMs}ms 拍到判定=${captureToJudgeMs}ms 整轮=${spent}ms " +
                            "previewOn=$previewOn torchWant=$wantTorch torchOn=${capture.torchEnabled} " +
                            "· ${decision.summary}",
                    )
                    AppLogger.i(
                        LOG_TAG,
                        "这一轮 #$roundCount 间隔 ${effectiveIntervalMs()}ms" +
                            (if (settingsStore.isMonitorPreviewActive()) "(预览倒计时3s)" else "") +
                            " · ${snore.lastHint}",
                    )
                    // 轮间也检查一次纯音频确认（不等下一张照片）
                    handleDecision(
                        shouldAlarm = fused.shouldAlarm,
                        summary = fused.reason,
                        audioAssisted = fused.audioAssisted,
                        // 角标带判定耗时，颜色匹配仍靠「正常/脱落」字样
                        resultLabel = "$resultLabel · ${decision.inferMs}ms",
                        frameEpochMs = captureEpoch,
                    )
                } catch (error: TimeoutCancellationException) {
                    noteFailure("这一张拍太久，下一轮再试")
                    maybeAudioOnlyAlarm()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    AppLogger.e(LOG_TAG, "这一轮拍摄失败", error)
                    capture.unbind()
                    noteFailure("这一轮没拍成：${AppLogger.summarize(error)}")
                    maybeAudioOnlyAlarm()
                }
                val cycleMs = effectiveIntervalMs()
                ensureWakeForCycle(cycleMs)
                val wait = cycleMs - (SystemClock.elapsedRealtime() - startedAt)
                if (wait > 0) {
                    // 等待期间分段醒来，便于音频已确认时尽快报警（不拖过 10–30s 目标）
                    var left = wait
                    while (left > 0 && isActive && settingsStore.settings().monitoringRequested) {
                        val slice = minOf(left, 1_500L)
                        delay(slice)
                        left -= slice
                        if (maybeAudioOnlyAlarm()) break
                    }
                }
            }
            snore.stop()
            audioStarted = false
        }
    }

    /** @return true 若已因音频触发报警 */
    private fun maybeAudioOnlyAlarm(): Boolean {
        if (alarm.isPlaying) return false
        if (SystemClock.elapsedRealtime() < ignoreFramesUntil) return false
        if (!snore.isConfirmed()) return false
        val fused = SnoreFusion.fuse(
            cameraShouldAlarm = false,
            cameraVote = null,
            snoreConfirmed = true,
            snoreConfirmedForMs = snore.confirmedForMs(),
            cameraSummary = "等待下一张照片",
        )
        if (!fused.shouldAlarm) return false
        handleDecision(true, fused.reason, audioAssisted = true)
        return true
    }

    private fun handleDecision(
        shouldAlarm: Boolean,
        summary: String,
        audioAssisted: Boolean = false,
        resultLabel: String? = null,
        frameEpochMs: Long = System.currentTimeMillis(),
    ) {
        val snoreBit = if (audioStarted) " · ${snore.lastHint}" else ""
        val detail = summary + snoreBit
        if (SystemClock.elapsedRealtime() < ignoreFramesUntil || alarm.isPlaying) {
            publish(
                RunStatus.MONITORING,
                "正在确认面罩",
                detail,
                capture.nightExtension,
                lastScoreHint = summary,
                bumpFrame = true,
                snoreHint = snore.lastHint,
                resultLabel = resultLabel,
                frameEpochMs = frameEpochMs,
            )
            return
        }
        if (shouldAlarm) {
            detector.resetDebounce()
            alarm.start()
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.notify(Notifications.ID_ALARM, Notifications.alarm(this))
            val head = if (audioAssisted) "可能脱落·请戴回" else "可能脱落·请戴回"
            publish(
                RunStatus.ALARMING,
                head,
                "铃声要你自己停。$detail",
                capture.nightExtension,
                lastScoreHint = summary,
                bumpFrame = true,
                snoreHint = snore.lastHint,
                resultLabel = resultLabel ?: "面罩脱落",
                frameEpochMs = frameEpochMs,
            )
            try {
                startActivity(
                    Intent(this, app.cpapmaskguard.AlarmActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    ),
                )
            } catch (error: Exception) {
                AppLogger.w(LOG_TAG, "不能直接打开停止页，改靠全屏通知", error)
            }
            return
        }
        val head = when {
            resultLabel?.contains("正常") == true -> "已守护·面罩正常"
            resultLabel?.contains("脱落") == true -> "正在确认面罩"
            resultLabel?.contains("未检测") == true -> "请先摆好手机"
            else -> "正在确认面罩"
        }
        publish(
            RunStatus.MONITORING,
            head,
            detail,
            capture.nightExtension,
            lastScoreHint = summary,
            bumpFrame = true,
            snoreHint = snore.lastHint,
            resultLabel = resultLabel,
            frameEpochMs = frameEpochMs,
        )
    }

    /** NO_PERSON 仅表示真正全黑/无人；有人戴罩绝不会走到这里显示「未检测到人」。 */
    private fun previewResultLabel(vote: FrameVote, alarmed: Boolean): String {
        if (alarmed) return "面罩脱落"
        return when (vote) {
            FrameVote.CLOSER_TO_MASK_ON -> "面罩正常"
            FrameVote.CLOSER_TO_MASK_OFF -> "面罩脱落"
            FrameVote.NO_PERSON -> "未检测到人"
            FrameVote.UNCERTAIN -> "不确定"
        }
    }

    private fun formatCaptureClock(epochMs: Long): String {
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(formatter)
    }

    private fun noteFailure(message: String) {
        consecutiveFailures += 1
        MonitorHealthScorer.noteRoundFailure()
        AppLogger.w(LOG_TAG, "监测轮次失败 #$consecutiveFailures: $message")
        val status = if (consecutiveFailures >= 5) RunStatus.ERROR else RunStatus.MONITORING
        val headline = if (consecutiveFailures >= 5) "请先摆好手机" else "正在确认面罩"
        publish(status, headline, message, capture.nightExtension, snoreHint = snore.lastHint)
        if (consecutiveFailures >= 5) {
            LogMailer.sendLogEmail(this, "连续失败>=5")
        }
    }

    private fun stopAlarm() {
        alarm.stop()
        detector.resetDebounce()
        Notifications.cancelAlarm(this)
        ignoreFramesUntil = SystemClock.elapsedRealtime() + 2_000
        if (settingsStore.settings().monitoringRequested) {
            publish(RunStatus.MONITORING, "已守护·面罩正常", "提醒已停止，守护还在继续。", capture.nightExtension, snoreHint = snore.lastHint)
        }
    }

    private fun stopFromUser() {
        settingsStore.setMonitoringRequested(false)
        loopJob?.cancel()
        loopJob = null
        snore.stop()
        audioStarted = false
        capture.unbind()
        alarm.stop()
        Notifications.cancelAlarm(this)
        releaseWake()
        roundCount = 0
        MonitorHealthScorer.resetSession()
        clearMonitorPreviewFiles()
        publish(RunStatus.STOPPED, "已停止", "守护已停。想用的时候再点开始守护。")
        ServiceCompat.stopForeground(this, Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showForeground(text: String) {
        val type = foregroundType()
        ServiceCompat.startForeground(
            this,
            Notifications.ID_MONITOR,
            Notifications.monitoring(this, text),
            type,
        )
    }

    private fun foregroundType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (Build.VERSION.SDK_INT >= 34 && snore.hasPermission()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    /** 预览开时临时缩短抓拍间隔，关掉后恢复设置值。 */
    private fun effectiveIntervalMs(): Long {
        val base = settingsStore.settings().intervalMs
        return if (settingsStore.isMonitorPreviewActive()) {
            minOf(base, CameraTuning.PREVIEW_CAPTURE_INTERVAL_MS)
        } else {
            base
        }
    }

    private fun saveLastFrame(source: File, captureEpochMs: Long) {
        try {
            val dir = File(cacheDir, "monitor").also { it.mkdirs() }
            val dest = File(dir, "last.jpg")
            val clock = formatCaptureClock(captureEpochMs)
            // 预览专用再压：缩小边长 + 质量 70 + 时:分:秒水印；失败则原样拷贝
            val wrote = recompressForPreview(source, dest, clock)
            if (!wrote) {
                source.copyTo(dest, overwrite = true)
            }
            AppLogger.i(LOG_TAG, "已写 last.jpg ${dest.length()}B previewOn=true recompress=$wrote clock=$clock")
        } catch (error: Exception) {
            // 写盘失败不影响检测主路径
            AppLogger.w(LOG_TAG, "保存 last.jpg 失败", error)
        }
    }

    /** @return true 若成功写出压缩预览图 */
    private fun recompressForPreview(source: File, dest: File, clockText: String): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val maxSide = 960
            var sample = 1
            var w = bounds.outWidth
            var h = bounds.outHeight
            while (w / sample > maxSide || h / sample > maxSide) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(source.absolutePath, opts) ?: return false
            val bmp = if (decoded.isMutable) decoded else decoded.copy(Bitmap.Config.ARGB_8888, true).also {
                if (it !== decoded) decoded.recycle()
            }
            try {
                drawTimestampWatermark(bmp, clockText)
                FileOutputStream(dest).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, CameraTuning.PREVIEW_JPEG_QUALITY, out)
                }
                true
            } finally {
                bmp.recycle()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun drawTimestampWatermark(bitmap: Bitmap, clockText: String) {
        val canvas = Canvas(bitmap)
        val textSize = (bitmap.width.coerceAtMost(bitmap.height) * 0.06f).coerceIn(28f, 56f)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.textSize = textSize
            style = Paint.Style.STROKE
            strokeWidth = textSize * 0.12f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            style = Paint.Style.FILL
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        val pad = textSize * 0.45f
        val x = pad
        val y = bitmap.height - pad
        canvas.drawText(clockText, x, y, stroke)
        canvas.drawText(clockText, x, y, fill)
    }

    private fun clearMonitorPreviewFiles() {
        try {
            File(cacheDir, "monitor/last.jpg").delete()
            File(cacheDir, "monitor/last_crop.jpg").delete()
        } catch (_: Exception) {
        }
    }

    private fun publish(
        status: RunStatus,
        headline: String,
        detail: String,
        nightExtension: Boolean? = MonitorRuntime.snapshot.value.nightExtension,
        lastScoreHint: String? = null,
        bumpFrame: Boolean = false,
        snoreHint: String? = null,
        resultLabel: String? = null,
        frameEpochMs: Long = System.currentTimeMillis(),
    ) {
        MonitorRuntime.update {
            it.copy(
                status = status,
                headline = headline,
                detail = detail,
                nightExtension = nightExtension,
                lastFrameEpochMs = if (bumpFrame) frameEpochMs else it.lastFrameEpochMs,
                roundCount = if (status == RunStatus.STOPPED) 0 else roundCount,
                lastScoreHint = lastScoreHint ?: it.lastScoreHint,
                snoreHint = snoreHint ?: it.snoreHint,
                previewResultLabel = resultLabel ?: it.previewResultLabel,
                captureLog = if (status == RunStatus.STOPPED) emptyList() else it.captureLog,
            )
        }
        if (status != RunStatus.STOPPED) {
            try {
                val manager = getSystemService(android.app.NotificationManager::class.java)
                manager.notify(Notifications.ID_MONITOR, Notifications.monitoring(this, detail))
            } catch (error: RuntimeException) {
                AppLogger.w(LOG_TAG, "通知刷新失败", error)
            }
        }
    }

    /** 按本轮间隔短持唤醒锁，避免长时间无超时占用。 */
    private fun ensureWakeForCycle(intervalMs: Long) {
        val power = getSystemService(PowerManager::class.java) ?: return
        if (wakeLock == null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "app.cpapmaskguard:monitor").apply {
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
        const val ACTION_START = "app.cpapmaskguard.START"
        const val ACTION_STOP = "app.cpapmaskguard.STOP"
        const val ACTION_STOP_ALARM = "app.cpapmaskguard.STOP_ALARM"

        val alive = AtomicBoolean(false)

        fun send(context: Context, action: String) {
            val intent = Intent(context, MonitorService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
