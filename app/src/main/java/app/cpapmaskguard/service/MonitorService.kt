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
import app.cpapmaskguard.capture.AimPreviewTarget
import app.cpapmaskguard.capture.CaptureController
import app.cpapmaskguard.capture.CameraTuning
import app.cpapmaskguard.core.CloudUploadPolicy
import app.cpapmaskguard.core.FrameVote
import app.cpapmaskguard.core.SettleGate
import app.cpapmaskguard.core.GuardHealth
import app.cpapmaskguard.core.GuardTelemetry
import app.cpapmaskguard.core.SceneChange
import app.cpapmaskguard.core.SnoreFusion
import app.cpapmaskguard.detector.BaselineStore
import app.cpapmaskguard.detector.MaskDetector
import app.cpapmaskguard.notify.Notifications
import app.cpapmaskguard.runtime.MonitorRuntime
import app.cpapmaskguard.runtime.RunStatus
import app.cpapmaskguard.settings.CloudUploadStore
import app.cpapmaskguard.settings.FillLightMode
import app.cpapmaskguard.settings.SettingsStore
import app.cpapmaskguard.FillLightActivity
import app.cpapmaskguard.health.MonitorHealthScorer
import app.cpapmaskguard.util.AppLogger
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
    private val cloudUpload by lazy { CloudUploadStore(this) }
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
    private var guardStartedElapsed = 0L
    private var announcedSettleEnd = false
    private var guardHealth = GuardHealth.State()
    private var sceneWatch = SceneChange.State()
    private var lastJudgmentElapsed = 0L

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
            publish(RunStatus.ERROR, "守护已中断，点此恢复", "守护已中断，点此恢复。${AppLogger.summarize(error)}")
            Notifications.notifyGuardFailure(
                this,
                "守护已中断，点此恢复",
                AppLogger.summarize(error),
                fullScreen = true,
            )
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
            guardStartedElapsed = SystemClock.elapsedRealtime()
            announcedSettleEnd = false
            val left = SettleGate.secondsRemaining(0)
            publish(
                RunStatus.STARTING,
                "请戴罩躺好（还剩 ${left}s）",
                "这一分钟内不会因为没戴面罩响铃。正在打开摄像头，请对准枕头。",
            )
            val prepared = try {
                withContext(Dispatchers.Default) {
                    detector.prepare(settingsStore.settings().calibrationSkipped)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLogger.e(LOG_TAG, "基准读失败", error)
                guardStartedElapsed = 0L
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
                    previewView = settlePreviewView(),
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
                guardStartedElapsed = 0L
                val head = if (mode == FillLightMode.FRONT_SCREEN) "前摄打不开" else "相机打不开"
                val detail = "守护已中断，点此恢复。$head。${AppLogger.summarize(error)}。请回到主界面点开始守护；可改选「后摄 + 自备光源」或重试。"
                publish(RunStatus.ERROR, "守护已中断，点此恢复", detail)
                val step = GuardHealth.onCameraBindFailure(guardHealth)
                guardHealth = step.state
                Notifications.notifyGuardFailure(this@MonitorService, "守护已中断，点此恢复", detail, fullScreen = true)
                return@launch
            }
            audioStarted = if (SnoreFusion.AUDIO_ALARM_ENABLED) snore.start() else false
            if (!SnoreFusion.AUDIO_ALARM_ENABLED) {
                AppLogger.i(LOG_TAG, "听鼾报警已关闭，不打开麦克风")
            } else if (!audioStarted) {
                AppLogger.w(LOG_TAG, "听鼾未启动（无权限或麦克风失败），仅相机监测")
            }
            roundCount = 0
            MonitorHealthScorer.resetSession()
            val modeHint = when (settingsStore.settings().fillLightMode) {
                FillLightMode.FRONT_SCREEN -> "前摄·屏幕补光"
                FillLightMode.REAR_AMBIENT -> "后摄·自备光源"
            }
            val settleLeft = if (inSettle()) {
                SettleGate.secondsRemaining(SystemClock.elapsedRealtime() - guardStartedElapsed)
            } else {
                0
            }
            publish(
                RunStatus.MONITORING,
                if (settleLeft > 0) "请戴罩躺好（还剩 ${settleLeft}s）" else "正在确认面罩",
                if (settleLeft > 0) {
                    "这一分钟内不会因为没戴面罩响铃。$prepared · $modeHint"
                } else if (audioStarted) {
                    "$prepared · $modeHint · 听鼾已开"
                } else if (SnoreFusion.AUDIO_ALARM_ENABLED) {
                    "$prepared · $modeHint · 听鼾未开（仅相机）"
                } else {
                    "$prepared · $modeHint · 听鼾报警已关闭"
                },
                capture.nightExtension,
            )
            ensureWakeForCycle(effectiveIntervalMs())
            while (isActive && settingsStore.settings().monitoringRequested) {
                val startedAt = SystemClock.elapsedRealtime()
                val cycleMs = effectiveIntervalMs()
                MonitorRuntime.update {
                    it.copy(
                        nextShotEpochMs = System.currentTimeMillis() + cycleMs,
                        judgePhase = "正在拍摄",
                    )
                }
                val settings = settingsStore.settings()
                try {
                    val shotInSettle = inSettle()
                    val wantFront = settings.fillLightMode == FillLightMode.FRONT_SCREEN
                    val wantTorch = settings.fillLightMode == FillLightMode.REAR_AMBIENT && settings.torchEnabled
                    onSettleEdge()
                    val wantPreview = settlePreviewView()
                    if (!capture.isBound ||
                        capture.boundWidth != settings.captureWidth ||
                        capture.boundHeight != settings.captureHeight ||
                        capture.usingFront != wantFront ||
                        capture.previewBound != (wantPreview != null)
                    ) {
                        capture.bind(
                            settings.captureWidth,
                            settings.captureHeight,
                            previewView = wantPreview,
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
                    saveLastFrame(file, captureEpoch)
                    MonitorRuntime.update { it.copy(lastFrameEpochMs = captureEpoch) }
                    // 拍照后小米常把灯掐掉；立刻重新点亮并 await
                    capture.setTorch(wantTorch, settings.torchLevel)
                    val previewOn = settingsStore.isMonitorPreviewActive()
                    val cropOut = when {
                        previewOn -> File(cacheDir, "monitor/last_crop.jpg").also { it.parentFile?.mkdirs() }
                        settings.debugSaveCrop -> File(cacheDir, "debug/last_crop.jpg")
                        else -> null
                    }
                    val gate = cloudUpload.state(captureEpoch)
                    val localOnly = !gate.uploadEnabled && gate.remaining() == 0
                    val decision = withContext(Dispatchers.Default) {
                        detector.onCapturedFile(
                            file,
                            cropOut,
                            uploadAllowed = gate.uploadEnabled,
                            holdMaskOffAlarm = !gate.uploadEnabled && gate.lastCloudWasMaskOff && !localOnly,
                            localFallback = localOnly,
                            circuit = cloudUpload.circuit(),
                            onPhase = { phase ->
                                MonitorRuntime.update { it.copy(judgePhase = phase) }
                            },
                        )
                    }
                    cloudUpload.saveCircuit(decision.circuit)
                    val updatedGate = recordCloudGate(gate, decision, captureEpoch)
                    noteSceneChange(updatedGate, decision.aimSignature, captureEpoch)
                    val captureToJudgeMs = SystemClock.elapsedRealtime() - captureElapsed
                    val fused = SnoreFusion.fuse(
                        cameraShouldAlarm = decision.shouldAlarm,
                        cameraVote = decision.vote,
                        snoreConfirmed = snore.isConfirmed(),
                        snoreConfirmedForMs = snore.confirmedForMs(),
                        cameraSummary = decision.summary,
                    )
                    val resultLabel = previewResultLabel(decision, fused.shouldAlarm)
                    val spent = SystemClock.elapsedRealtime() - startedAt
                    val timeText = formatCaptureClock(captureEpoch)
                    // 界面列表：一眼能看拍摄时间、结果、判定耗时
                    val uiLine =
                        "$timeText  $resultLabel  判定${decision.inferMs}ms  拍到判定${captureToJudgeMs}ms  整轮${spent}ms"
                    // 预览开或关都写入，开预览时列表立刻可见
                    MonitorRuntime.appendCaptureLog(uiLine)
                    file.delete()
                    consecutiveFailures = 0
                    guardHealth = GuardHealth.onJudgment(guardHealth)
                    lastJudgmentElapsed = SystemClock.elapsedRealtime()
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
                    if (decision.promptTokens >= 0 || decision.completionTokens >= 0) {
                        AppLogger.i(
                            LOG_TAG,
                            "usage #$roundCount prompt=${decision.promptTokens} completion=${decision.completionTokens}",
                        )
                    }
                    val telemetry = GuardTelemetry.metricLine(
                        kind = "round",
                        epochMs = captureEpoch,
                        detail = resultLabel,
                        promptTokens = decision.promptTokens,
                        completionTokens = decision.completionTokens,
                    )
                    AppLogger.i(LOG_TAG, telemetry)
                    if (settingsStore.imageDonationEnabled()) {
                        AppLogger.i(LOG_TAG, "图片捐赠已打开，但还没有上传地址，这一帧只记了指标")
                    }
                    AppLogger.i(
                        LOG_TAG,
                        "延时 #$roundCount 拍摄=$timeText 判定=$resultLabel vote=${decision.vote} " +
                            "infer=${decision.inferMs}ms 拍到判定=${captureToJudgeMs}ms 整轮=${spent}ms " +
                            "usage=${decision.promptTokens}/${decision.completionTokens} " +
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
                        capturedInSettle = shotInSettle,
                        judgePhase = resultLabel,
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
                        noteStall()
                    }
                }
            }
            snore.stop()
            audioStarted = false
        }
    }

    /** @return true 若已因音频触发报警 */
    private fun maybeAudioOnlyAlarm(): Boolean {
        if (!SnoreFusion.AUDIO_ALARM_ENABLED) return false
        if (alarm.isPlaying) return false
        onSettleEdge()
        if (inSettle()) return false
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
        capturedInSettle: Boolean = false,
        judgePhase: String? = null,
    ) {
        onSettleEdge()
        val snoreBit = if (audioStarted) " · ${snore.lastHint}" else ""
        val detail = summary + snoreBit
        if (inSettle() || capturedInSettle) {
            detector.resetDebounce()
            val left = SettleGate.secondsRemaining(SystemClock.elapsedRealtime() - guardStartedElapsed)
            publish(
                RunStatus.MONITORING,
                "请戴罩躺好（还剩 ${left}s）",
                "这一分钟内不会因为没戴面罩响铃。$detail",
                capture.nightExtension,
                lastScoreHint = summary,
                bumpFrame = true,
                snoreHint = snore.lastHint,
                resultLabel = resultLabel,
                frameEpochMs = frameEpochMs,
                judgePhase = judgePhase,
            )
            return
        }
        if (alarm.isPlaying) {
            publish(
                RunStatus.ALARMING,
                "可能脱落·请戴回",
                detail,
                capture.nightExtension,
                lastScoreHint = summary,
                bumpFrame = true,
                snoreHint = snore.lastHint,
                resultLabel = resultLabel ?: "面罩脱落",
                frameEpochMs = frameEpochMs,
                judgePhase = judgePhase ?: "面罩脱落",
            )
            return
        }
        if (SystemClock.elapsedRealtime() < ignoreFramesUntil) {
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
                judgePhase = judgePhase,
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
                judgePhase = judgePhase ?: "面罩脱落",
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
            judgePhase = judgePhase,
        )
    }

    /** 对准失败用软提示，不写成脱落。暂停上传时，只有仍按上次脱落在响才标脱落。 */
    private fun previewResultLabel(decision: MaskDetector.FrameDecision, alarmed: Boolean): String {
        if (decision.aimInvalid && !alarmed) return "镜头可能没对准"
        if (decision.localOnly && !alarmed) {
            return when (decision.vote) {
                FrameVote.CLOSER_TO_MASK_ON -> "面罩正常"
                FrameVote.CLOSER_TO_MASK_OFF -> "面罩脱落"
                FrameVote.NO_PERSON -> "未检测到人"
                FrameVote.UNCERTAIN -> "本机兜底"
            }
        }
        if (decision.cloudSkipped && !alarmed) return "已暂停上传"
        if (alarmed) return "面罩脱落"
        return when (decision.vote) {
            FrameVote.CLOSER_TO_MASK_ON -> "面罩正常"
            FrameVote.CLOSER_TO_MASK_OFF -> "面罩脱落"
            FrameVote.NO_PERSON -> "未检测到人"
            FrameVote.UNCERTAIN -> "不确定"
        }
    }

    private fun recordCloudGate(
        before: CloudUploadPolicy.State,
        decision: MaskDetector.FrameDecision,
        epochMs: Long,
    ): CloudUploadPolicy.State {
        val vote = decision.cloudVote
        val updated = when {
            decision.aimInvalid -> cloudUpload.noteAimInvalid(epochMs)
            vote == FrameVote.CLOSER_TO_MASK_OFF && inSettle() -> before
            vote != null -> cloudUpload.noteCloud(vote, epochMs)
            else -> {
                val rolled = CloudUploadPolicy.roll(before, epochMs)
                if (rolled != before) cloudUpload.noteAimInvalid(epochMs) else before
            }
        }
        if (before.uploadEnabled && !updated.uploadEnabled) {
            sceneWatch = SceneChange.State()
            AppLogger.i(
                LOG_TAG,
                "连续 ${updated.consecutiveMaskOff} 次云端面罩脱落，已停止上传。今晚还可恢复 ${updated.remaining()} 次",
            )
        }
        return updated
    }

    /** 暂停期间画面明显变化，连续多帧确认后自动恢复上传一次。次数用尽就停在本机兜底。 */
    private fun noteSceneChange(
        gate: CloudUploadPolicy.State,
        signature: List<Float>,
        epochMs: Long,
    ) {
        if (gate.uploadEnabled) {
            sceneWatch = SceneChange.State()
            return
        }
        val step = SceneChange.observe(sceneWatch, signature)
        sceneWatch = step.state
        if (!step.resume) return
        when (val outcome = cloudUpload.resume(epochMs)) {
            is CloudUploadPolicy.Resume.Accepted -> {
                sceneWatch = SceneChange.State()
                AppLogger.i(LOG_TAG, "画面变化已确认，自动恢复上传一次。今晚还可恢复 ${outcome.state.remaining()} 次")
            }
            is CloudUploadPolicy.Resume.Denied -> {
                sceneWatch = sceneWatch.copy(usedThisPause = true)
                AppLogger.i(LOG_TAG, "画面有变化，但今晚恢复次数已用完，继续本机兜底")
            }
            is CloudUploadPolicy.Resume.AlreadyOn -> sceneWatch = SceneChange.State()
        }
    }

    private fun noteStall() {
        val step = GuardHealth.onTick(
            guardHealth,
            lastJudgmentElapsedMs = lastJudgmentElapsed,
            nowElapsedMs = SystemClock.elapsedRealtime(),
            intervalMs = effectiveIntervalMs(),
        )
        guardHealth = step.state
        if (!step.justTripped) return
        val reason = "超过 ${GuardHealth.STALL_INTERVALS} 个抓拍间隔没有新的判定"
        publish(RunStatus.ERROR, "守护失效", reason, capture.nightExtension, snoreHint = snore.lastHint)
        Notifications.notifyGuardFailure(this, "守护失效", reason, fullScreen = false)
    }

    private fun formatCaptureClock(epochMs: Long): String {
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(formatter)
    }

    private fun noteFailure(message: String) {
        consecutiveFailures += 1
        MonitorHealthScorer.noteRoundFailure()
        val step = GuardHealth.onJudgmentFailure(guardHealth, System.currentTimeMillis())
        guardHealth = step.state
        AppLogger.w(LOG_TAG, "监测轮次失败 #$consecutiveFailures: $message")
        val tripped = step.state.tripped && step.state.reason == GuardHealth.Reason.JUDGMENT
        val status = if (tripped) RunStatus.ERROR else RunStatus.MONITORING
        val headline = if (tripped) "守护失效" else "正在确认面罩"
        val detail = if (tripped) "连续判定失败。$message" else message
        publish(status, headline, detail, capture.nightExtension, snoreHint = snore.lastHint, judgePhase = message)
        if (step.justTripped) {
            Notifications.notifyGuardFailure(this, "守护失效", "连续判定失败。$message", fullScreen = false)
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
        guardStartedElapsed = 0L
        announcedSettleEnd = false
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
        if (Build.VERSION.SDK_INT >= 34 && SnoreFusion.AUDIO_ALARM_ENABLED && snore.hasPermission()) {
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
            AppLogger.i(LOG_TAG, "已写 last.jpg ${dest.length()}B recompress=$wrote clock=$clock")
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
        judgePhase: String? = null,
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
                settleUntilElapsed = when {
                    status == RunStatus.STOPPED -> 0L
                    inSettle() -> guardStartedElapsed + SettleGate.WINDOW_MS
                    else -> 0L
                },
                nextShotEpochMs = if (status == RunStatus.STOPPED) 0L else it.nextShotEpochMs,
                judgePhase = when {
                    status == RunStatus.STOPPED -> null
                    judgePhase != null -> judgePhase
                    else -> it.judgePhase
                },
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

    private fun inSettle(): Boolean {
        if (guardStartedElapsed <= 0L) return false
        return SystemClock.elapsedRealtime() - guardStartedElapsed < SettleGate.WINDOW_MS
    }

    private fun settlePreviewView() = if (inSettle()) {
        AimPreviewTarget.view?.takeIf { it.isAttachedToWindow }
    } else {
        null
    }

    /** 缓冲走完的那一下清掉坐床边积下的脱落计数和打呼确认。 */
    private fun onSettleEdge() {
        if (guardStartedElapsed <= 0L || announcedSettleEnd || inSettle()) return
        announcedSettleEnd = true
        detector.resetDebounce()
        snore.clearConfirmation()
        AppLogger.i(LOG_TAG, "躺好缓冲结束，开始看面罩脱落")
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
