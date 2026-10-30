package app.cpapmaskguard

import android.Manifest
import android.graphics.BitmapFactory
import android.view.WindowManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.cpapmaskguard.capture.AimPreview
import app.cpapmaskguard.capture.AimPreviewTarget
import app.cpapmaskguard.capture.CameraTuning
import app.cpapmaskguard.core.SettleGate
import app.cpapmaskguard.core.SnoreFusion
import app.cpapmaskguard.core.BatteryEstimate
import app.cpapmaskguard.core.DetectionConfig
import app.cpapmaskguard.core.MonitorPolicy
import app.cpapmaskguard.databinding.ActivityMainBinding
import app.cpapmaskguard.detector.BaselineStore
import app.cpapmaskguard.burnin.BurnInPhase
import app.cpapmaskguard.burnin.BurnInRuntime
import app.cpapmaskguard.health.MonitorHealthScorer
import app.cpapmaskguard.util.AppLogger
import app.cpapmaskguard.util.LogMailer
import app.cpapmaskguard.oem.KeepAliveGuide
import app.cpapmaskguard.runtime.MonitorRuntime
import app.cpapmaskguard.runtime.MonitorSnapshot
import app.cpapmaskguard.runtime.RunStatus
import app.cpapmaskguard.service.BurnInService
import app.cpapmaskguard.service.MonitorService
import app.cpapmaskguard.settings.FillLightMode
import app.cpapmaskguard.core.CloudUploadPolicy
import app.cpapmaskguard.settings.CloudUploadStore
import app.cpapmaskguard.settings.SettingsStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsStore
    private lateinit var cloudUpload: CloudUploadStore
    private lateinit var baselines: BaselineStore
    private var rendering = false
    private var aimPreview: AimPreview? = null
    private var aimSignature: String? = null
    private var settleTickerJob: Job? = null
    private var roundTickerJob: Job? = null
    private var monitorDecodeJob: Job? = null
    private var monitorShownEpoch = 0L
    private var suppressKilledBannerUntil = 0L
    private var previewExpireJob: Job? = null
    private var previewUiJob: Job? = null
    private var previewDecodeJob: Job? = null
    private var lastShownFrameEpoch = 0L
    private var lastShownFrameModified = 0L
    private var lastPreviewRemainBucket = -1
    private var lastCountdownDigit = -1
    private var previewUiStartedAt = 0L
    private var healthTickerJob: Job? = null
    private var lastHealth: MonitorHealthScorer.ScoreResult? = null
    private var burnInFinishHandled: Long = 0L
    private var pendingBurnIn: Boolean = false
    private var moreExpanded: Boolean = false
    private var pendingOnboardingChecklist: Boolean = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val notifyOk = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!cameraOk || !notifyOk) {
            pendingOnboardingChecklist = false
            toast("需要相机和通知权限，否则守护开不起来。")
            val hard = buildList {
                add(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (hard.any { result[it] == false && !shouldShowRequestPermissionRationale(it) }) {
                KeepAliveGuide.openAppDetails(this)
            }
        } else {
            if (pendingOnboardingChecklist) {
                pendingOnboardingChecklist = false
                showOnboardingChecklist()
            } else if (pendingBurnIn) {
                pendingBurnIn = false
                startBurnIn()
            } else {
                if (SnoreFusion.AUDIO_ALARM_ENABLED && !micOk) toast(getString(R.string.mic_denied_toast))
                maybePromptBatteryThenStart()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AimPreviewTarget.view = binding.aimPreview
        aimPreview = AimPreview(this, this)
        settings = SettingsStore(this)
        cloudUpload = CloudUploadStore(this)
        baselines = BaselineStore(this)
        baselines.migrateLegacyIfNeeded()
        binding.primaryButton.setOnClickListener { onPrimary() }
        binding.secondaryButton.setOnClickListener {
            MonitorService.send(this, MonitorService.ACTION_STOP)
        }
        binding.resumeUploadButton.setOnClickListener { onResumeUpload() }
        binding.calibrateButton.setOnClickListener { openCalibration() }
        binding.clearBaselineButton.setOnClickListener { confirmClearBaselines() }
        binding.healthReportButton.setOnClickListener { sendHealthReportEmail() }
        binding.burnInButton.setOnClickListener { startBurnIn() }
        binding.burnInCancelButton.setOnClickListener { cancelBurnIn() }
        binding.moreToggle.setOnClickListener { toggleMorePanel() }
        binding.viewLogsButton.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.lowEndSwitch.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            settings.setLowEnd(checked)
            bindSettings()
            toast("间隔和分辨率已按这个模式重置，下次开始守护时用新的。")
        }
        binding.resGroup.setOnCheckedChangeListener { _, checkedId ->
            if (rendering) return@setOnCheckedChangeListener
            if (checkedId == R.id.resHigh) settings.setResolution(1280, 720) else settings.setResolution(640, 480)
        }
        binding.fillLightGroup.setOnCheckedChangeListener { _, checkedId ->
            if (rendering) return@setOnCheckedChangeListener
            val mode = if (checkedId == R.id.fillFrontScreen) {
                FillLightMode.FRONT_SCREEN
            } else {
                FillLightMode.REAR_AMBIENT
            }
            val prev = settings.settings().fillLightMode
            settings.setFillLightMode(mode)
            applyFillLightPanels(mode)
            if (prev != mode && baselines.isComplete()) {
                toast(getString(R.string.fill_mode_recalibrate_toast))
            } else {
                toast(
                    if (mode == FillLightMode.FRONT_SCREEN) {
                        "已选前摄 + 屏幕补光"
                    } else {
                        "已选后摄 + 自备光源（默认关手电筒）"
                    },
                )
            }
            render(MonitorRuntime.snapshot.value)
        }
        binding.screenBrightnessSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || rendering) return@addOnChangeListener
            settings.setScreenBrightness(value)
            binding.screenBrightnessLabel.text =
                getString(R.string.screen_brightness_level, (value * 100).toInt())
        }
        binding.torchSwitch.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            settings.setTorchEnabled(checked)
            refreshAimPreview()
        }
        binding.torchSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || rendering) return@addOnChangeListener
            val level = (value / 100f).coerceIn(CameraTuning.MIN_TORCH_LEVEL, 0.6f)
            settings.setTorchLevel(level)
            binding.torchLevelLabel.text = getString(R.string.torch_level, value.toInt())
            refreshAimPreview()
        }
        binding.previewSwitch.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            if (checked) {
                settings.enableMonitorPreview()
                if (!moreExpanded) {
                    moreExpanded = true
                    applyMorePanel()
                }
                applyHalfScreenPreviewHeight()
                binding.previewContainer.visibility = View.VISIBLE
                binding.previewPlaceholder.visibility = View.VISIBLE
                binding.lastFramePreview.visibility = View.INVISIBLE
                binding.previewCountdown.visibility = View.VISIBLE
                binding.previewCountdown.text = CameraTuning.PREVIEW_COUNTDOWN_SEC.toString()
                binding.previewCaptureLog.visibility = View.VISIBLE
                binding.previewCaptureLog.text = getString(R.string.preview_log_empty)
                lastCountdownDigit = -1
                previewUiStartedAt = System.currentTimeMillis()
                schedulePreviewAutoOff()
                toast(getString(R.string.preview_on_toast))
                startPreviewUiTicker()
                scrollToPreviewArea()
                val snap = MonitorRuntime.snapshot.value
                val runningNow = when (snap.status) {
                    RunStatus.MONITORING, RunStatus.STARTING, RunStatus.ALARMING -> true
                    RunStatus.ERROR -> MonitorService.alive.get()
                    RunStatus.STOPPED -> false
                }
                if (!runningNow) {
                    toast(getString(R.string.preview_need_monitor_toast))
                }
            } else {
                settings.disableMonitorPreview()
                previewExpireJob?.cancel()
                previewExpireJob = null
                previewUiJob?.cancel()
                previewUiJob = null
                previewDecodeJob?.cancel()
                previewDecodeJob = null
                clearKeepScreenOn()
                hidePreviewFrame()
            }
            render(MonitorRuntime.snapshot.value)
        }
        binding.debugCropSwitch.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            settings.setDebugSaveCrop(checked)
        }
        binding.batteryButton.setOnClickListener { KeepAliveGuide.openBatteryOptimization(this) }
        binding.autostartButton.setOnClickListener {
            // Generic first: app details (autostart / background on most OEMs). MIUI not required.
            if (!KeepAliveGuide.openAutostartGuidance(this)) {
                toast("打不开应用信息页，请到系统设置里手动允许后台运行。")
            }
        }
        binding.miuiBatteryButton.setOnClickListener {
            // Optional Xiaomi-only helper; fall back to generic battery settings.
            if (!KeepAliveGuide.openMiuiBattery(this)) {
                KeepAliveGuide.openBatteryOptimization(this)
                toast("当前不是小米或打不开小米省电页，已打开通用电池设置。")
            }
        }
        binding.fullScreenButton.setOnClickListener {
            if (!KeepAliveGuide.openFullScreenSettings(this)) {
                toast("打不开全屏提醒设置。")
            }
        }
        binding.soundButton.setOnClickListener { KeepAliveGuide.openDndOrSound(this) }
        binding.appDetailsButton.setOnClickListener { KeepAliveGuide.openAppDetails(this) }
        binding.fullScreenButton.visibility = if (Build.VERSION.SDK_INT >= 34) View.VISIBLE else View.GONE
        bindSettings()
        applyHalfScreenPreviewHeight()
        applyMorePanel()
        if (savedInstanceState == null) {
            maybeShowOnboarding()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MonitorRuntime.snapshot.collect { render(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BurnInRuntime.snapshot.collect { renderBurnIn(it) }
            }
        }
        startHealthTicker()
    }

    override fun onResume() {
        super.onResume()
        bindSettings()
        refreshBaseline()
        refreshKilledBanner(MonitorRuntime.snapshot.value)
        render(MonitorRuntime.snapshot.value)
        startHealthTicker()
        refreshHealthScore()
        refreshAimPreview()
    }

    override fun onDestroy() {
        previewExpireJob?.cancel()
        previewUiJob?.cancel()
        previewDecodeJob?.cancel()
        healthTickerJob?.cancel()
        settleTickerJob?.cancel()
        if (!MonitorService.alive.get()) aimPreview?.stop()
        clearKeepScreenOn()
        super.onDestroy()
    }

    private fun bindSettings() {
        rendering = true
        val current = settings.settings()
        binding.lowEndSwitch.isChecked = current.lowEndMode
        binding.resLow.isChecked = current.captureWidth <= 800
        binding.resHigh.isChecked = current.captureWidth > 800
        binding.debugCropSwitch.isChecked = current.debugSaveCrop
        binding.previewSwitch.isChecked = settings.isMonitorPreviewActive()
        if (current.fillLightMode == FillLightMode.FRONT_SCREEN) {
            binding.fillFrontScreen.isChecked = true
        } else {
            binding.fillRearAmbient.isChecked = true
        }
        applyFillLightPanels(current.fillLightMode)
        binding.screenBrightnessSlider.value = current.screenBrightness.coerceIn(0.05f, 1f)
        binding.screenBrightnessLabel.text =
            getString(R.string.screen_brightness_level, (current.screenBrightness * 100).toInt())
        binding.torchSwitch.isChecked = current.torchEnabled
        binding.torchSlider.value = (current.torchLevel * 100f).coerceIn(1f, 60f)
        binding.torchLevelLabel.text = getString(R.string.torch_level, (current.torchLevel * 100).toInt().coerceIn(1, 60))
        configureSlider(current.intervalSec, current.lowEndMode)
        if (settings.isMonitorPreviewActive()) {
            schedulePreviewAutoOff()
        }
        rendering = false
        binding.keepAliveTitle.text = if (KeepAliveGuide.isXiaomiFamily()) {
            "已识别为小米 / 红米，通用设置之外还可点小米专属项"
        } else {
            "请先完成通用电池与后台设置；下方小米专属项可忽略"
        }
        val xiaomiOnly = KeepAliveGuide.isXiaomiFamily()
        binding.miuiBatteryButton.visibility = if (xiaomiOnly) android.view.View.VISIBLE else android.view.View.GONE
        binding.deviceLine.text = "${KeepAliveGuide.deviceLine()} · ${KeepAliveGuide.ramLine(this)}"
    }

    private fun configureSlider(seconds: Int, lowEnd: Boolean) {
        val range = MonitorPolicy.intervalRange(lowEnd)
        val target = seconds.coerceIn(range)
        val slider = binding.intervalSlider
        slider.clearOnChangeListeners()
        slider.valueFrom = 5f
        slider.valueTo = 15f
        slider.value = target.toFloat()
        slider.valueFrom = range.first.toFloat()
        slider.valueTo = range.last.toFloat()
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || rendering) return@addOnChangeListener
            settings.setInterval(value.toInt())
            binding.intervalLabel.text = getString(R.string.interval_value, value.toInt())
        }
        binding.intervalLabel.text = getString(R.string.interval_value, target)
    }

    private fun settleRemaining(snapshot: MonitorSnapshot): Int {
        val until = snapshot.settleUntilElapsed
        if (until <= 0L) return 0
        val leftMs = until - SystemClock.elapsedRealtime()
        if (leftMs <= 0L) return 0
        return ((leftMs + 999L) / 1000L).toInt()
    }

    private fun render(snapshot: MonitorSnapshot) {
        val lifestyle = lifestyleStatus(snapshot)
        binding.statusHeadline.text = lifestyle.first
        binding.statusDetail.text = lifestyle.second
        val current = settings.settings()
        val night = when (snapshot.nightExtension) {
            true -> " · 夜景扩展开"
            false -> " · 无夜景扩展"
            null -> ""
        }
        val cam = when (current.fillLightMode) {
            FillLightMode.FRONT_SCREEN -> "前摄·屏幕补光${(current.screenBrightness * 100).toInt()}%"
            FillLightMode.REAR_AMBIENT -> "后摄·自备光源"
        }
        val torch = if (current.fillLightMode == FillLightMode.REAR_AMBIENT && current.torchEnabled) {
            " · 手电${(current.torchLevel * 100).toInt()}%"
        } else {
            ""
        }
        val mode = if (current.lowEndMode) "低端模式" else "标准模式"
        binding.techLine.text =
            "$mode · $cam$torch · 每 ${current.intervalSec} 秒 · ${current.captureWidth}×${current.captureHeight}$night"
        val alarming = snapshot.status == RunStatus.ALARMING
        val running = when (snapshot.status) {
            RunStatus.MONITORING, RunStatus.STARTING, RunStatus.ALARMING -> true
            RunStatus.ERROR -> MonitorService.alive.get()
            RunStatus.STOPPED -> false
        }
        binding.secondaryButton.visibility = if (alarming) View.VISIBLE else View.GONE
        binding.secondaryButton.text = getString(R.string.stop_guard)
        renderCloudQuota()
        binding.primaryButton.text = when {
            alarming -> getString(R.string.stop_alarm_short)
            running -> getString(R.string.stop_guard)
            !baselines.isComplete() && !current.calibrationSkipped -> getString(R.string.need_baseline_first)
            else -> getString(R.string.start_guard)
        }
        val titleColor = when (snapshot.status) {
            RunStatus.ALARMING -> R.color.amber
            RunStatus.ERROR -> R.color.danger
            RunStatus.MONITORING, RunStatus.STARTING -> R.color.accent
            RunStatus.STOPPED -> R.color.text
        }
        binding.statusHeadline.setTextColor(ContextCompat.getColor(this, titleColor))
        binding.skipBanner.visibility = if (current.calibrationSkipped) View.VISIBLE else View.GONE
        refreshKilledBanner(snapshot)
        updatePreviewUi(snapshot, running)
        updateMonitorRoundCard(snapshot, running)
        updateAimWindow(snapshot, running)
        if (!running) refreshBaseline()
    }

    private fun updateMonitorRoundCard(snapshot: MonitorSnapshot, running: Boolean) {
        if (!running) {
            roundTickerJob?.cancel()
            roundTickerJob = null
            monitorShownEpoch = 0L
            binding.monitorLastFrame.setImageDrawable(null)
            binding.monitorRoundCard.visibility = View.GONE
            return
        }
        binding.monitorRoundCard.visibility = View.VISIBLE
        startRoundTicker()
        val now = System.currentTimeMillis()
        val remainMs = snapshot.nextShotEpochMs - now
        binding.monitorCountdown.text = if (snapshot.nextShotEpochMs <= 0L) {
            getString(R.string.monitor_next_soon)
        } else if (remainMs <= 0L) {
            getString(R.string.monitor_next_soon)
        } else {
            val seconds = ((remainMs + 999L) / 1000L).toInt()
            getString(R.string.monitor_next_shot, seconds)
        }
        val phase = snapshot.judgePhase?.takeIf { it.isNotBlank() } ?: getString(R.string.status_checking)
        binding.monitorJudgePhase.text = phase
        val phaseColor = when {
            phase.contains("没拍成") || phase.contains("失败") || phase.contains("太久") -> R.color.danger
            phase.contains("脱落") -> R.color.amber
            else -> R.color.text
        }
        binding.monitorJudgePhase.setTextColor(ContextCompat.getColor(this, phaseColor))
        if (snapshot.lastFrameEpochMs > 0L) {
            val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(snapshot.lastFrameEpochMs))
            binding.monitorCaptureTime.text = getString(R.string.monitor_capture_time, clock)
        } else {
            binding.monitorCaptureTime.text = getString(R.string.monitor_waiting_first)
        }
        val lastFile = File(cacheDir, "monitor/last.jpg")
        val hasFrame = lastFile.exists() && lastFile.length() > 0L && snapshot.lastFrameEpochMs > 0L
        if (!hasFrame) {
            binding.monitorLastFrame.visibility = View.GONE
            return
        }
        binding.monitorLastFrame.visibility = View.VISIBLE
        if (snapshot.lastFrameEpochMs != monitorShownEpoch) {
            monitorShownEpoch = snapshot.lastFrameEpochMs
            reloadMonitorFrame(lastFile)
        }
    }

    private fun startRoundTicker() {
        if (roundTickerJob?.isActive == true) return
        roundTickerJob = lifecycleScope.launch {
            while (true) {
                delay(1_000)
                val snap = MonitorRuntime.snapshot.value
                val running = when (snap.status) {
                    RunStatus.MONITORING, RunStatus.STARTING, RunStatus.ALARMING -> true
                    RunStatus.ERROR -> MonitorService.alive.get()
                    RunStatus.STOPPED -> false
                }
                if (!running) break
                updateMonitorRoundCard(snap, true)
            }
        }
    }

    private fun reloadMonitorFrame(lastFile: File) {
        monitorDecodeJob?.cancel()
        val targetW = binding.monitorLastFrame.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val targetH = binding.monitorLastFrame.height.takeIf { it > 0 } ?: targetW
        val path = lastFile.absolutePath
        monitorDecodeJob = lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                decodePreviewSampled(path, targetW, targetH)
            }
            if (bmp != null) {
                binding.monitorLastFrame.setImageBitmap(bmp)
                binding.monitorLastFrame.visibility = View.VISIBLE
            }
        }
    }

    private fun updateAimWindow(snapshot: MonitorSnapshot, running: Boolean) {
        val settling = settleRemaining(snapshot) > 0
        val show = !running || settling
        binding.aimPreviewCard.visibility = if (show) View.VISIBLE else View.GONE
        if (settling) startSettleTicker()
        if (!running && !settling) refreshAimPreview()
    }

    private fun startSettleTicker() {
        if (settleTickerJob?.isActive == true) return
        settleTickerJob = lifecycleScope.launch {
            delay(1_000)
            while (true) {
                val snap = MonitorRuntime.snapshot.value
                if (settleRemaining(snap) <= 0 && snap.settleUntilElapsed == 0L) break
                render(snap)
                delay(1_000)
            }
        }
    }

    private fun refreshAimPreview() {
        if (!::binding.isInitialized) return
        if (MonitorService.alive.get()) {
            aimSignature = null
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            binding.aimPreviewCaption.setText(R.string.aim_preview_need_camera)
            aimSignature = null
            return
        }
        binding.aimPreviewCaption.setText(R.string.aim_preview_caption)
        val current = settings.settings()
        val front = current.fillLightMode == FillLightMode.FRONT_SCREEN
        val torch = !front && current.torchEnabled
        val signature = "$front|$torch|${current.torchLevel}"
        if (signature == aimSignature) return
        aimSignature = signature
        lifecycleScope.launch {
            if (MonitorService.alive.get()) return@launch
            aimPreview?.start(binding.aimPreview, front, torch, current.torchLevel)
        }
    }

    private fun updatePreviewUi(snapshot: MonitorSnapshot, running: Boolean) {
        val previewOn = settings.isMonitorPreviewActive()
        if (binding.previewSwitch.isChecked != previewOn && !rendering) {
            rendering = true
            binding.previewSwitch.isChecked = previewOn
            rendering = false
        }
        if (previewOn) {
            schedulePreviewAutoOff()
            startPreviewUiTicker()
            applyHalfScreenPreviewHeight()
            binding.previewContainer.visibility = View.VISIBLE
            binding.previewCaptureLog.visibility = View.VISIBLE
        } else {
            previewUiJob?.cancel()
            previewUiJob = null
            previewDecodeJob?.cancel()
            previewDecodeJob = null
            hidePreviewFrame()
            clearKeepScreenOn()
            return
        }

        refreshPreviewFrame(snapshot, running)
        updateCountdownOverlay(snapshot, running)
        updatePreviewResultAndLog(snapshot)
        if (previewOn && running) {
            updatePreviewStatusLines(snapshot)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            clearKeepScreenOn()
        }
    }

    /** 只刷新预览图与占位，不碰整页其它控件。 */
    private fun refreshPreviewFrame(snapshot: MonitorSnapshot, running: Boolean) {
        val lastFile = File(cacheDir, "monitor/last.jpg")
        val hasFrame = running && lastFile.exists() && lastFile.length() > 0L && snapshot.lastFrameEpochMs > 0L
        if (!hasFrame) {
            // 已有旧图时保留，避免每 tick 清空闪一下；仅占位文案更新
            if (binding.lastFramePreview.drawable == null) {
                binding.lastFramePreview.visibility = View.INVISIBLE
                binding.previewPlaceholder.visibility = View.VISIBLE
            }
            binding.previewPlaceholder.text = if (!running) {
                getString(R.string.preview_placeholder)
            } else {
                "预览已开：正在等第一张照片…"
            }
            if (!running) {
                lastShownFrameEpoch = 0L
                lastShownFrameModified = 0L
            }
        } else {
            binding.previewPlaceholder.visibility = View.GONE
            binding.lastFramePreview.visibility = View.VISIBLE
            val modified = lastFile.lastModified()
            if (snapshot.lastFrameEpochMs != lastShownFrameEpoch || modified != lastShownFrameModified) {
                lastShownFrameEpoch = snapshot.lastFrameEpochMs
                lastShownFrameModified = modified
                reloadPreviewBitmap(lastFile)
            }
        }
    }

    private fun updatePreviewStatusLines(snapshot: MonitorSnapshot) {
        val ago = if (snapshot.lastFrameEpochMs > 0L) {
            val sec = ((System.currentTimeMillis() - snapshot.lastFrameEpochMs) / 1000L).coerceAtLeast(0L)
            when {
                sec < 3L -> "刚刚拍到"
                sec < 60L -> "${sec}秒前拍到"
                else -> "${sec / 60L}分钟前拍到"
            }
        } else {
            "还没拍到"
        }
        val hint = snapshot.lastScoreHint ?: ""
        val round = if (snapshot.roundCount > 0) "第 ${snapshot.roundCount} 轮 · " else ""
        val snore = snapshot.snoreHint?.let { " · $it" }.orEmpty()
        // 技术细节进次级 techLine，主路径保持生活化文案
        val techExtra = "$round$ago · $hint$snore".trim(' ', '·')
        val remain = settings.previewRemainingMs()
        val remainSecTotal = (remain / 1000L).coerceAtLeast(0L).toInt()
        if (remainSecTotal != lastPreviewRemainBucket) {
            lastPreviewRemainBucket = remainSecTotal
            val remainMin = remainSecTotal / 60
            val remainSec = remainSecTotal % 60
            val baseTech = binding.techLine.text.toString()
                .substringBefore(" · 预览")
                .substringBefore(" · 第 ")
            binding.techLine.text = listOf(
                baseTech,
                techExtra.takeIf { it.isNotBlank() },
                getString(R.string.preview_remaining, remainMin, remainSec),
            ).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
        }
    }

    private fun reloadPreviewBitmap(lastFile: File) {
        previewDecodeJob?.cancel()
        val targetW = binding.lastFramePreview.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val targetH = binding.lastFramePreview.height.takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels / 2)
        val path = lastFile.absolutePath
        val sizeHint = lastFile.length()
        previewDecodeJob = lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                decodePreviewSampled(path, targetW, targetH)
            }
            if (bmp != null) {
                // 直接换图，不清空旧 drawable，避免闪黑掉帧
                binding.lastFramePreview.setImageBitmap(bmp)
                binding.lastFramePreview.visibility = View.VISIBLE
                binding.previewPlaceholder.visibility = View.GONE
                Log.i("CpapPreview", "preview bitmap ok ${bmp.width}x${bmp.height} from ${sizeHint}B target=${targetW}x${targetH}")
            } else {
                Log.w("CpapPreview", "decode null for $path size=$sizeHint")
                if (binding.lastFramePreview.drawable == null) {
                    binding.previewPlaceholder.visibility = View.VISIBLE
                    binding.previewPlaceholder.text = "预览已开：照片解码失败，等下一张…"
                    binding.lastFramePreview.visibility = View.INVISIBLE
                }
            }
        }
    }

    private fun decodePreviewSampled(path: String, reqW: Int, reqH: Int): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > reqW * 2 || bounds.outHeight / sample > reqH * 2) {
                sample *= 2
            }
            // 半屏至少压到合适像素，避免全尺寸 JPEG 进 ImageView
            while (bounds.outWidth / sample > reqW || bounds.outHeight / sample > reqH) {
                if (sample >= 16) break
                val next = sample * 2
                if (bounds.outWidth / next < reqW / 2 && bounds.outHeight / next < reqH / 2) break
                sample = next
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(path, opts)
        } catch (error: Exception) {
            Log.w("CpapPreview", "decodePreviewSampled failed", error)
            null
        }
    }

    private fun hidePreviewFrame() {
        binding.previewContainer.visibility = View.GONE
        binding.lastFramePreview.setImageDrawable(null)
        binding.lastFramePreview.visibility = View.INVISIBLE
        binding.previewPlaceholder.visibility = View.VISIBLE
        binding.previewCountdown.visibility = View.GONE
        binding.previewResultBadge.visibility = View.GONE
        binding.previewTimeOverlay.visibility = View.GONE
        binding.previewCaptureLog.visibility = View.GONE
        binding.previewCaptureLog.text = ""
        lastShownFrameEpoch = 0L
        lastShownFrameModified = 0L
        lastCountdownDigit = -1
    }

    private fun scrollToPreviewArea() {
        binding.mainScroll.post {
            binding.previewContainer.requestFocus()
            binding.mainScroll.smoothScrollTo(0, binding.previewContainer.top.coerceAtLeast(0))
        }
    }

    /** 预览容器强制半屏高度，开预览即占位，避免 ScrollView 把区域挤没。 */
    private fun applyHalfScreenPreviewHeight() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        val half = (metrics.heightPixels / 2).coerceAtLeast(
            (360 * resources.displayMetrics.density).toInt(),
        )
        val container = binding.previewContainer
        val lp = container.layoutParams
        if (lp.height != half) {
            lp.height = half
            container.layoutParams = lp
        }
        container.minimumHeight = half
        container.requestLayout()
    }

    private fun startPreviewUiTicker() {
        if (previewUiJob?.isActive == true) return
        if (previewUiStartedAt <= 0L) previewUiStartedAt = System.currentTimeMillis()
        previewUiJob = lifecycleScope.launch {
            while (settings.isMonitorPreviewActive()) {
                delay(200)
                if (!settings.isMonitorPreviewActive()) break
                // 轻量刷新：倒计时 + last.jpg mtime + 结果/日志，避免整页 render
                val snap = MonitorRuntime.snapshot.value
                val running = when (snap.status) {
                    RunStatus.MONITORING, RunStatus.STARTING, RunStatus.ALARMING -> true
                    RunStatus.ERROR -> MonitorService.alive.get()
                    RunStatus.STOPPED -> false
                }
                refreshPreviewFrame(snap, running)
                updateCountdownOverlay(snap, running)
                updatePreviewResultAndLog(snap)
                if (running) updatePreviewStatusLines(snap)
            }
        }
    }

    /**
     * 按最近一帧时间推算下一抓拍点，大字显示 3→2→1；
     * 尚无帧时从打开预览起本地循环，让用户看出「活着」。
     */
    private fun updateCountdownOverlay(snapshot: MonitorSnapshot, running: Boolean) {
        if (!settings.isMonitorPreviewActive()) {
            binding.previewCountdown.visibility = View.GONE
            return
        }
        val cycleMs = CameraTuning.PREVIEW_CAPTURE_INTERVAL_MS
        val now = System.currentTimeMillis()
        val anchor = when {
            snapshot.lastFrameEpochMs > 0L -> snapshot.lastFrameEpochMs
            previewUiStartedAt > 0L -> previewUiStartedAt
            else -> now
        }
        val elapsed = (now - anchor).coerceAtLeast(0L)
        val intoCycle = elapsed % cycleMs
        val remainMs = cycleMs - intoCycle
        // 显示 3/2/1；最后约 0.35s 显示「拍」提示即将刷新静帧
        val digit = when {
            !running && snapshot.lastFrameEpochMs <= 0L -> {
                val sec = ((remainMs + 999L) / 1000L).toInt().coerceIn(1, CameraTuning.PREVIEW_COUNTDOWN_SEC)
                sec
            }
            remainMs <= 350L -> 0
            else -> ((remainMs + 999L) / 1000L).toInt().coerceIn(1, CameraTuning.PREVIEW_COUNTDOWN_SEC)
        }
        if (digit != lastCountdownDigit) {
            lastCountdownDigit = digit
            binding.previewCountdown.visibility = View.VISIBLE
            binding.previewCountdown.text = if (digit == 0) "拍" else digit.toString()
        } else if (binding.previewCountdown.visibility != View.VISIBLE) {
            binding.previewCountdown.visibility = View.VISIBLE
            binding.previewCountdown.text = if (digit == 0) "拍" else digit.toString()
        }
    }

    private fun updatePreviewResultAndLog(snapshot: MonitorSnapshot) {
        if (!settings.isMonitorPreviewActive()) return
        val label = snapshot.previewResultLabel
        if (!label.isNullOrBlank()) {
            binding.previewResultBadge.visibility = View.VISIBLE
            binding.previewResultBadge.text = label
            val color = when {
                label.contains("脱落") -> 0xE6B45309.toInt()
                label.contains("正常") -> 0xE616A34A.toInt()
                else -> 0xCC111827.toInt()
            }
            binding.previewResultBadge.setBackgroundColor(color)
        } else {
            binding.previewResultBadge.visibility = View.VISIBLE
            binding.previewResultBadge.text = getString(R.string.preview_result_waiting)
            binding.previewResultBadge.setBackgroundColor(0xCC111827.toInt())
        }
        if (snapshot.lastFrameEpochMs > 0L) {
            val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(snapshot.lastFrameEpochMs))
            binding.previewTimeOverlay.visibility = View.VISIBLE
            binding.previewTimeOverlay.text = clock
        }
        binding.previewCaptureLog.visibility = View.VISIBLE
        binding.previewCaptureLog.text = if (snapshot.captureLog.isEmpty()) {
            getString(R.string.preview_log_empty)
        } else {
            // 每行：拍摄时间 · 结果 · 判定耗时 · 整轮（由 MonitorService 写入）
            snapshot.captureLog.take(12).joinToString("\n")
        }
    }

    private fun schedulePreviewAutoOff() {
        previewExpireJob?.cancel()
        val remain = settings.previewRemainingMs()
        if (remain <= 0L) {
            if (binding.previewSwitch.isChecked) {
                settings.disableMonitorPreview()
                rendering = true
                binding.previewSwitch.isChecked = false
                rendering = false
                clearKeepScreenOn()
                hidePreviewFrame()
                previewUiJob?.cancel()
                previewUiJob = null
                previewDecodeJob?.cancel()
                previewDecodeJob = null
                toast(getString(R.string.preview_auto_off))
                render(MonitorRuntime.snapshot.value)
            }
            return
        }
        previewExpireJob = lifecycleScope.launch {
            delay(remain)
            settings.disableMonitorPreview()
            rendering = true
            binding.previewSwitch.isChecked = false
            rendering = false
            clearKeepScreenOn()
            hidePreviewFrame()
            previewUiJob?.cancel()
            previewUiJob = null
                previewDecodeJob?.cancel()
                previewDecodeJob = null
            toast(getString(R.string.preview_auto_off))
            render(MonitorRuntime.snapshot.value)
        }
    }

    private fun clearKeepScreenOn() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun refreshKilledBanner(snapshot: MonitorSnapshot) {
        val show = settings.settings().monitoringRequested &&
            !MonitorService.alive.get() &&
            snapshot.status == RunStatus.STOPPED &&
            SystemClock.elapsedRealtime() > suppressKilledBannerUntil
        binding.killedBanner.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun refreshBaseline() {
        val current = settings.settings()
        binding.baselineLine.text = when {
            current.calibrationSkipped -> "基准：调试跳过。监测不会判断面罩。"
            !baselines.isComplete() ->
                "基准：还不够。不戴 / 戴着每类至少 ${DetectionConfig.BASELINE_MIN_PER_KIND} 张（目标 ${DetectionConfig.BASELINE_TARGET_PER_KIND} 张多角度）。"
            else -> {
                val meta = baselines.meta()
                val whenText = meta?.let { formatRange(it.withoutAt, it.withAt) }.orEmpty()
                val hint = meta?.hint() ?: "基准已保存在这台手机上"
                "基准：$hint$whenText"
            }
        }
    }

    private fun formatRange(start: Long, end: Long): String {
        if (start <= 0L && end <= 0L) return ""
        val formatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
        val zone = ZoneId.systemDefault()
        val first = if (start > 0L) Instant.ofEpochMilli(start).atZone(zone).format(formatter) else "?"
        val second = if (end > 0L) Instant.ofEpochMilli(end).atZone(zone).format(formatter) else "?"
        return "（$first / $second）"
    }

    private fun renderCloudQuota() {
        val state = cloudUpload.state()
        val showButton = !state.uploadEnabled
        val showCount = showButton || state.resumesUsed > 0
        binding.resumeQuotaLine.visibility = if (showCount) View.VISIBLE else View.GONE
        binding.resumeUploadButton.visibility = if (showButton) View.VISIBLE else View.GONE
        if (!showCount && !showButton) return
        val remaining = state.remaining()
        binding.resumeQuotaLine.text = if (showButton && remaining == 0) {
            getString(R.string.resume_exhausted)
        } else {
            getString(R.string.resume_remaining, remaining)
        }
        binding.resumeUploadButton.isEnabled = remaining > 0
    }

    private fun onResumeUpload() {
        when (val outcome = cloudUpload.resume()) {
            is CloudUploadPolicy.Resume.Accepted ->
                toast(getString(R.string.resume_accepted, outcome.state.remaining()))
            is CloudUploadPolicy.Resume.Denied ->
                toast(getString(R.string.resume_exhausted))
            is CloudUploadPolicy.Resume.AlreadyOn ->
                toast(getString(R.string.resume_already))
        }
        render(MonitorRuntime.snapshot.value)
    }

    private fun onPrimary() {
        val status = MonitorRuntime.snapshot.value.status
        val running = when (status) {
            RunStatus.MONITORING, RunStatus.STARTING, RunStatus.ALARMING -> true
            RunStatus.ERROR -> MonitorService.alive.get()
            RunStatus.STOPPED -> false
        }
        when {
            status == RunStatus.ALARMING -> MonitorService.send(this, MonitorService.ACTION_STOP_ALARM)
            running -> MonitorService.send(this, MonitorService.ACTION_STOP)
            else -> beginMonitoring()
        }
    }

    private fun beginMonitoring() {
        if (BurnInService.alive.get() || BurnInRuntime.snapshot.value.phase == BurnInPhase.RUNNING) {
            toast(getString(R.string.burn_in_busy_burn))
            return
        }
        val current = settings.settings()
        if (!baselines.isComplete() && !current.calibrationSkipped) {
            openCalibration()
            return
        }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        maybePromptBatteryThenStart()
    }

    private fun maybePromptBatteryThenStart() {
        // 电量保守估算：建议插电但不强制
        val estimate = currentBatteryEstimate()
        MaterialAlertDialogBuilder(this)
            .setTitle(if (estimate.suggestPlugIn) "建议插电后再监测" else "电量估算")
            .setMessage(estimate.message)
            .setPositiveButton(R.string.start_guard) { _, _ -> maybePromptOemThenStart() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun maybePromptOemThenStart() {
        val power = getSystemService(PowerManager::class.java)
        val ignoring = power?.isIgnoringBatteryOptimizations(packageName) == true
        if (!ignoring && !settings.batteryPromptShown()) {
            settings.setBatteryPromptShown()
            MaterialAlertDialogBuilder(this)
                .setTitle("睡眠时别被省电清掉")
                .setMessage("建议允许忽略电池优化。这只挡住系统自带的省电。小米还要另外开自启动，并把省电策略设成无限制。")
                .setPositiveButton("去设置") { _, _ -> KeepAliveGuide.openBatteryOptimization(this) }
                .setNegativeButton("仍然开始") { _, _ -> startNow() }
                .show()
            return
        }
        startNow()
    }

    private fun currentBatteryEstimate(): BatteryEstimate.Estimate {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 50
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryEstimate.estimate(pct, charging)
    }

    private fun startNow() {
        suppressKilledBannerUntil = SystemClock.elapsedRealtime() + 8_000
        binding.killedBanner.visibility = View.GONE
        val until = SystemClock.elapsedRealtime() + SettleGate.WINDOW_MS
        MonitorRuntime.update {
            it.copy(
                status = RunStatus.STARTING,
                headline = getString(R.string.settle_headline, 60),
                detail = getString(R.string.settle_detail),
                settleUntilElapsed = until,
            )
        }
        binding.aimPreviewCard.visibility = View.VISIBLE
        aimSignature = null
        aimPreview?.stop()
        settings.setMonitoringRequested(true)
        val mode = settings.settings().fillLightMode
        binding.aimPreview.post {
            try {
                MonitorService.send(this, MonitorService.ACTION_START)
            } catch (error: Exception) {
                settings.setMonitoringRequested(false)
                MonitorRuntime.update {
                    it.copy(
                        status = RunStatus.ERROR,
                        headline = getString(R.string.status_place_phone),
                        detail = error.message ?: "系统拒绝了前台服务",
                        settleUntilElapsed = 0L,
                    )
                }
            }
        }
        if (mode == FillLightMode.FRONT_SCREEN) {
            FillLightActivity.launchIfNeeded(this)
        }
        maybeShowXiaomiGuide()
    }

    private fun applyFillLightPanels(mode: FillLightMode) {
        val front = mode == FillLightMode.FRONT_SCREEN
        binding.frontBrightnessPanel.visibility = if (front) View.VISIBLE else View.GONE
        binding.rearTorchPanel.visibility = if (front) View.GONE else View.VISIBLE
        binding.fillModeHint.text = getString(
            if (front) R.string.fill_mode_front_hint else R.string.fill_mode_rear_hint,
        )
    }

    private fun maybeShowXiaomiGuide() {
        if (!KeepAliveGuide.isXiaomiFamily() || settings.xiaomiGuideShown()) return
        settings.setXiaomiGuideShown()
        MaterialAlertDialogBuilder(this)
            .setTitle("小米 / 红米要再开几项")
            .setMessage("只留着前台通知，MIUI 或 HyperOS 仍可能在锁屏后把相机清掉。请允许自启动，省电策略选无限制，并在最近任务里锁定本应用。本应用不会开机自动打开相机。")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun openCalibration() {
        lifecycleScope.launch {
            if (MonitorService.alive.get()) {
                MonitorService.send(this@MainActivity, MonitorService.ACTION_STOP)
                val deadline = SystemClock.elapsedRealtime() + 3_000
                while (MonitorService.alive.get() && SystemClock.elapsedRealtime() < deadline) {
                    delay(100)
                }
            }
            startActivity(Intent(this@MainActivity, CalibrationActivity::class.java))
        }
    }

    private fun confirmClearBaselines() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清除基准照片？")
            .setMessage("会删掉保存在本机的多张面罩基准。清除后要重新拍摄才能监测。")
            .setPositiveButton("清除") { _, _ ->
                baselines.clear()
                settings.setCalibrationSkipped(false)
                refreshBaseline()
                render(MonitorRuntime.snapshot.value)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (SnoreFusion.AUDIO_ALARM_ENABLED) permissions += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        return permissions
    }

    private fun missingPermissions(): List<String> =
        requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }


    private fun startHealthTicker() {
        if (healthTickerJob?.isActive == true) return
        healthTickerJob = lifecycleScope.launch {
            while (true) {
                refreshHealthScore()
                val running = when (MonitorRuntime.snapshot.value.status) {
                    RunStatus.MONITORING, RunStatus.STARTING, RunStatus.ALARMING -> true
                    RunStatus.ERROR -> MonitorService.alive.get()
                    RunStatus.STOPPED -> false
                }
                delay(if (running) 3_000L else 8_000L)
            }
        }
    }

    private fun refreshHealthScore() {
        val result = try {
            MonitorHealthScorer.score(this)
        } catch (error: Exception) {
            AppLogger.w(LOG_TAG, "健康分采样失败", error)
            return
        }
        lastHealth = result
        binding.healthScoreTitle.text = getString(R.string.health_score_title, result.score)
        binding.healthScoreDetail.text = result.reasonLine
        val colorRes = when {
            result.score >= 80 -> R.color.health_green
            result.score >= 60 -> R.color.health_yellow
            else -> R.color.health_red
        }
        val color = ContextCompat.getColor(this, colorRes)
        binding.healthScoreTitle.setTextColor(color)
        binding.healthAdviseBanner.visibility = if (result.adviseStop) View.VISIBLE else View.GONE
        binding.healthScoreCard.strokeColor = color
        // 正常时不占主屏；<60 红字提示
        if (result.score < 60) {
            binding.healthWarnBanner.visibility = View.VISIBLE
            binding.healthWarnBanner.text = getString(
                R.string.health_warn_low,
                result.score,
                result.reasonLine,
            )
        } else {
            binding.healthWarnBanner.visibility = View.GONE
        }
        // 未监测时也周期性写一行日志（降频：仅分变化或每约 30s）
        val stampBucket = result.sampledAtMs / 30_000L
        val prevBucket = (binding.healthScoreTitle.tag as? Long) ?: -1L
        if (stampBucket != prevBucket) {
            binding.healthScoreTitle.tag = stampBucket
            AppLogger.i(LOG_TAG, MonitorHealthScorer.logLine(result))
        }
    }


    private fun startBurnIn() {
        if (BurnInService.alive.get() || BurnInRuntime.snapshot.value.phase == BurnInPhase.RUNNING) {
            toast(getString(R.string.burn_in_busy_burn))
            return
        }
        val monitoring = when (MonitorRuntime.snapshot.value.status) {
            RunStatus.MONITORING, RunStatus.STARTING, RunStatus.ALARMING -> true
            RunStatus.ERROR -> MonitorService.alive.get()
            RunStatus.STOPPED -> false
        }
        if (monitoring || MonitorService.alive.get()) {
            toast(getString(R.string.burn_in_busy_monitor))
            return
        }
        // 烤机不强制麦克风；缺相机/通知时请求权限
        val missing = missingPermissions()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            toast(getString(R.string.burn_in_need_camera))
            pendingBurnIn = true
            permissionLauncher.launch(missing.toTypedArray().ifEmpty {
                arrayOf(Manifest.permission.CAMERA)
            })
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingBurnIn = true
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        // 烤机前关掉预览，贴近睡眠场景
        if (settings.isMonitorPreviewActive()) {
            settings.disableMonitorPreview()
            binding.previewSwitch.isChecked = false
            hidePreviewFrame()
        }
        burnInFinishHandled = 0L
        BurnInRuntime.update {
            it.copy(
                phase = BurnInPhase.RUNNING,
                elapsedMs = 0L,
                durationMs = BurnInRuntime.DEFAULT_DURATION_MS,
                sampleCount = 0,
                progressLine = getString(R.string.burn_in_progress, 0, 5),
                detailLine = "正在启动烤机…",
                report = null,
                finishToken = 0L,
            )
        }
        try {
            BurnInService.send(this, BurnInService.ACTION_START)
            toast(getString(R.string.burn_in_start_toast))
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (error: Exception) {
            BurnInRuntime.resetIdle("烤机启动失败：${AppLogger.summarize(error)}")
            toast("烤机启动失败：${AppLogger.summarize(error)}")
        }
    }

    private fun cancelBurnIn() {
        if (!BurnInService.alive.get() &&
            BurnInRuntime.snapshot.value.phase != BurnInPhase.RUNNING
        ) {
            return
        }
        BurnInService.send(this, BurnInService.ACTION_CANCEL)
    }

    private fun renderBurnIn(snap: app.cpapmaskguard.burnin.BurnInSnapshot) {
        val running = snap.phase == BurnInPhase.RUNNING
        binding.burnInProgressCard.visibility = if (running) View.VISIBLE else View.GONE
        binding.burnInButton.isEnabled = !running
        binding.burnInButton.alpha = if (running) 0.45f else 1f
        if (running) {
            binding.burnInProgressTitle.text = getString(R.string.burn_in_running)
            binding.burnInProgressLine.text = snap.progressLine.ifBlank {
                val done = (snap.elapsedMs / 60_000L).toInt()
                val total = ((snap.durationMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
                getString(R.string.burn_in_progress, done, total)
            }
            val extras = buildList {
                snap.currentScore?.let { add("健康分 $it") }
                snap.currentTempC?.let { add(String.format("电池 %.1f°C", it)) }
                snap.currentCaptureToJudgeMs?.let { add("延时 ${it}ms") }
            }
            binding.burnInProgressDetail.text = listOf(snap.detailLine, extras.joinToString(" · "))
                .filter { it.isNotBlank() }
                .joinToString("\n")
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            // 非烤机中且非监测预览时，不必一直亮屏（监测预览另有逻辑）
            if (!settings.isMonitorPreviewActive()) {
                // 不在这里 clear，避免与预览抢；结束烤机时再清一次
            }
        }
        if (snap.finishToken > 0L && snap.finishToken != burnInFinishHandled) {
            burnInFinishHandled = snap.finishToken
            clearKeepScreenOnIfIdle()
            when (snap.phase) {
                BurnInPhase.COMPLETED -> {
                    val report = snap.report
                    if (report != null && LogMailer.sendBurnInReport(this, report)) {
                        toast(getString(R.string.burn_in_mail_toast))
                    }
                }
                BurnInPhase.CANCELLED -> {
                    val report = snap.report
                    if (report != null && report.sampleCount > 0) {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("烤机已取消")
                            .setMessage("已采样 ${report.sampleCount} 次。要发送不完整烤机报告吗？")
                            .setPositiveButton(R.string.burn_in_send_incomplete) { _, _ ->
                                if (LogMailer.sendBurnInReport(this, report)) {
                                    toast(getString(R.string.burn_in_mail_toast))
                                }
                            }
                            .setNegativeButton("不发") { _, _ ->
                                toast(getString(R.string.burn_in_cancelled_toast))
                            }
                            .show()
                    } else {
                        toast(getString(R.string.burn_in_cancelled_toast))
                    }
                }
                BurnInPhase.ERROR -> {
                    toast(snap.detailLine.ifBlank { "烤机失败" })
                    snap.report?.let { report ->
                        if (report.sampleCount > 0) {
                            MaterialAlertDialogBuilder(this)
                                .setTitle("烤机异常结束")
                                .setMessage(snap.detailLine + "\n是否仍发送已采到的报告？")
                                .setPositiveButton(R.string.burn_in_send_incomplete) { _, _ ->
                                    LogMailer.sendBurnInReport(this, report)
                                }
                                .setNegativeButton("不发", null)
                                .show()
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun clearKeepScreenOnIfIdle() {
        val previewOn = settings.isMonitorPreviewActive()
        val monitoring = when (MonitorRuntime.snapshot.value.status) {
            RunStatus.MONITORING, RunStatus.STARTING, RunStatus.ALARMING -> true
            else -> false
        }
        if (!previewOn || !monitoring) {
            // 与 updatePreviewUi 一致：仅预览+监测时才 keep；其余清掉烤机留下的 flag
            if (!(previewOn && monitoring)) {
                clearKeepScreenOn()
            }
        }
    }


    private fun sendHealthReportEmail() {
        val result = lastHealth ?: try {
            MonitorHealthScorer.score(this)
        } catch (error: Exception) {
            toast("暂时采不到健康数据：${AppLogger.summarize(error)}")
            return
        }
        if (LogMailer.sendHealthReport(this, result, force = true)) {
            toast(getString(R.string.health_send_toast))
        }
    }


    private fun toggleMorePanel() {
        moreExpanded = !moreExpanded
        applyMorePanel()
        if (moreExpanded) {
            binding.mainScroll.post {
                binding.moreToggle.parent?.let {
                    binding.mainScroll.smoothScrollTo(0, binding.moreToggle.top.coerceAtLeast(0))
                }
            }
        }
    }

    private fun applyMorePanel() {
        binding.morePanel.visibility = if (moreExpanded) View.VISIBLE else View.GONE
        binding.moreToggle.text = getString(
            if (moreExpanded) R.string.more_toggle_open else R.string.more_toggle_closed,
        )
    }

    /** 主屏只显示生活化状态；引擎名/ms/分辨率等留在 techLine。 */
    private fun lifestyleStatus(snapshot: MonitorSnapshot): Pair<String, String> {
        val left = settleRemaining(snapshot)
        if (left > 0 && snapshot.status != RunStatus.STOPPED && snapshot.status != RunStatus.ALARMING) {
            return getString(R.string.settle_headline, left) to getString(R.string.settle_detail)
        }
        val label = snapshot.previewResultLabel.orEmpty()
        val detailFallback = snapshot.detail
        return when (snapshot.status) {
            RunStatus.STOPPED -> {
                if (detailFallback.contains("已停")) {
                    getString(R.string.status_stopped) to detailFallback
                } else {
                    getString(R.string.status_idle) to getString(R.string.status_idle_detail)
                }
            }
            RunStatus.STARTING ->
                getString(R.string.status_checking) to "正在打开相机，请保持对准枕头。"
            RunStatus.MONITORING -> when {
                label.contains("正常") ->
                    getString(R.string.status_guarding_ok) to "守护中，面罩看起来还在。"
                label.contains("脱落") ->
                    getString(R.string.status_checking) to "正在再确认一次，请保持姿势。"
                label.contains("未检测") ->
                    getString(R.string.status_place_phone) to "镜头里好像没看到人，请把手机对准枕头。"
                label.contains("对准") ->
                    getString(R.string.status_checking) to getString(R.string.aim_soft_hint)
                label.contains("暂停") ->
                    getString(R.string.status_checking) to getString(R.string.upload_paused_detail)
                label.contains("本机") ->
                    getString(R.string.status_checking) to getString(R.string.resume_exhausted)
                else ->
                    getString(R.string.status_checking) to "正在看面罩还在不在。"
            }
            RunStatus.ALARMING -> {
                val body = if (snapshot.lastScoreHint?.contains("镜头可能没对准") == true) {
                    R.string.alarm_detail_aim
                } else {
                    R.string.alarm_detail
                }
                getString(R.string.status_off_alert) to getString(body)
            }
            RunStatus.ERROR -> {
                val head = when {
                    detailFallback.contains("守护已中断") -> "守护已中断，点此恢复"
                    detailFallback.contains("连续判定失败") || detailFallback.contains("没有新的判定") -> "守护失效"
                    else -> getString(R.string.status_place_phone)
                }
                head to detailFallback.ifBlank { "相机不太稳定，请检查摆放与权限。" }
            }
        }
    }

    private fun maybeShowOnboarding() {
        if (settings.onboardingDone()) {
            // 已引导过且仍无样张：不强制跳校准，等用户点主按钮
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.onboarding_camera_title)
            .setMessage(R.string.onboarding_camera_body)
            .setCancelable(false)
            .setPositiveButton(R.string.onboarding_camera_allow) { _, _ ->
                val missing = missingPermissions()
                if (missing.isNotEmpty()) {
                    pendingOnboardingChecklist = true
                    permissionLauncher.launch(missing.toTypedArray())
                } else {
                    showOnboardingChecklist()
                }
            }
            .show()
    }

    private fun showOnboardingChecklist() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.onboarding_checklist_title)
            .setMessage(R.string.onboarding_checklist_body)
            .setCancelable(false)
            .setPositiveButton(R.string.onboarding_go_calibrate) { _, _ ->
                settings.setOnboardingDone()
                openCalibration()
            }
            .setNegativeButton(R.string.onboarding_later) { _, _ ->
                settings.setOnboardingDone()
            }
            .show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
