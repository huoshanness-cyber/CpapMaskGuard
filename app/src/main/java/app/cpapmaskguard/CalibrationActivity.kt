package app.cpapmaskguard

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.cpapmaskguard.capture.CameraTuning
import app.cpapmaskguard.util.AppLogger
import app.cpapmaskguard.util.LogMailer
import app.cpapmaskguard.capture.CaptureController
import app.cpapmaskguard.core.DetectionConfig
import app.cpapmaskguard.core.EmbeddingClusterClassifier
import app.cpapmaskguard.BuildConfig
import app.cpapmaskguard.databinding.ActivityCalibrationBinding
import app.cpapmaskguard.detector.BaselineStore
import app.cpapmaskguard.detector.BitmapDecode
import app.cpapmaskguard.detector.FaceCropper
import app.cpapmaskguard.detector.ImageLimits
import app.cpapmaskguard.detector.LumaStats
import app.cpapmaskguard.detector.TfliteEmbedder
import app.cpapmaskguard.settings.SettingsStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 多角度多张基准：每类（不戴 / 戴）拍 [DetectionConfig.BASELINE_TARGET_PER_KIND] 张，
 * 至少 [DetectionConfig.BASELINE_MIN_PER_KIND] 张可完成。睡觉只关心面罩，不检测眼睛。
 */
class CalibrationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCalibrationBinding
    private lateinit var store: BaselineStore
    private lateinit var settings: SettingsStore
    private lateinit var capture: CaptureController
    private var embedder: TfliteEmbedder? = null
    private var step = BaselineStore.Kind.WITHOUT_MASK
    private var pending: AnalyzedShot? = null
    private var countdownJob: Job? = null
    private var busy = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) openCamera() else {
            toast("没有相机权限，没法拍摄基准。")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = BaselineStore(this)
        store.migrateLegacyIfNeeded()
        settings = SettingsStore(this)
        capture = CaptureController(this, this)
        embedder = TfliteEmbedder(this)
        step = when {
            store.count(BaselineStore.Kind.WITHOUT_MASK) < DetectionConfig.BASELINE_TARGET_PER_KIND ->
                BaselineStore.Kind.WITHOUT_MASK
            store.count(BaselineStore.Kind.WITH_MASK) < DetectionConfig.BASELINE_TARGET_PER_KIND ->
                BaselineStore.Kind.WITH_MASK
            else -> BaselineStore.Kind.WITHOUT_MASK
        }
        binding.shutterButton.setOnClickListener { captureStill() }
        binding.countdownButton.setOnClickListener { startCountdown() }
        binding.confirmButton.setOnClickListener { confirmPending() }
        binding.retakeButton.setOnClickListener { discardPending() }
        binding.skipButton.setOnClickListener { confirmSkip() }
        val showSkip = BuildConfig.DEBUG
        binding.skipButton.visibility = if (showSkip) View.VISIBLE else View.GONE
        binding.skipHint.visibility = if (showSkip) View.VISIBLE else View.GONE
        binding.restartButton.setOnClickListener { restart() }
        binding.doneEarlyButton.setOnClickListener { tryFinishEarly() }
        renderStep()
        showPreviewPhase()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        discardPending()
        capture.close()
        embedder?.close()
        super.onDestroy()
    }

    private fun openCamera() {
        val size = settings.settings()
        lifecycleScope.launch {
            try {
                capture.bind(
                    size.captureWidth,
                    size.captureHeight,
                    binding.preview,
                    useFrontCamera = size.useFrontCamera,
                    enableTorch = size.torchEnabled && !size.useFrontCamera,
                    torchLevel = size.torchLevel,
                )
                setShootingEnabled(pending == null && !busy)
                binding.hintLabel.text = hintFor(step) + nightSuffix()
            } catch (error: Exception) {
                val summary = AppLogger.summarize(error)
                AppLogger.e(LOG_TAG, "校准页打开相机失败", error)
                binding.hintLabel.text = "相机打不开：$summary。可回主界面查看日志。"
                toast("相机打不开：$summary")
                LogMailer.sendLogEmail(this@CalibrationActivity, "校准开相机失败")
            }
        }
    }

    private fun nightSuffix(): String {
        val cam = if (capture.usingFront) "前摄" else "后摄"
        return " · $cam" + if (capture.nightExtension) " · 夜景扩展" else ""
    }

    private fun startCountdown() {
        if (busy || pending != null) return
        countdownJob?.cancel()
        countdownJob = lifecycleScope.launch {
            setShootingEnabled(false)
            binding.countdown.visibility = View.VISIBLE
            for (second in CameraTuning.CALIBRATION_COUNTDOWN_SEC downTo 1) {
                binding.countdown.text = second.toString()
                delay(1_000)
            }
            binding.countdown.visibility = View.GONE
            setShootingEnabled(true)
            captureStill()
        }
    }

    private fun captureStill() {
        if (busy || pending != null || !capture.isBound) {
            if (!capture.isBound) toast("相机还没准备好。")
            return
        }
        busy = true
        setShootingEnabled(false)
        lifecycleScope.launch {
            try {
                val file = capture.takePicture()
                val analyzed = withContext(Dispatchers.Default) { analyze(file) }
                pending = analyzed
                showConfirmPhase(analyzed)
            } catch (error: Exception) {
                toast("没拍成：${error.message ?: "未知错误"}")
            } finally {
                busy = false
                if (pending == null) setShootingEnabled(true)
            }
        }
    }

    private fun analyze(file: File): AnalyzedShot {
        val bitmap = BitmapDecode.decode(file, ImageLimits.DECODE_MAX_SIDE)
        val luma = LumaStats.of(bitmap)
        val emb = try {
            val crop = FaceCropper.crop(bitmap)
            try {
                embedder?.takeIf { it.available }?.embed(crop.lower)
            } finally {
                if (!crop.full.isRecycled) crop.full.recycle()
                if (!crop.lower.isRecycled) crop.lower.recycle()
            }
        } catch (_: Throwable) {
            null
        }
        return AnalyzedShot(file, bitmap, luma.tooDarkForPerson, emb)
    }

    private fun confirmPending() {
        val shot = pending ?: return
        store.add(step, shot.file, shot.embedding, shot.dark)
        discardPending()
        val target = DetectionConfig.BASELINE_TARGET_PER_KIND
        if (store.count(step) < target) {
            renderStep()
            showPreviewPhase()
            toast("已保存。请换个轻微角度再拍一张。")
            return
        }
        // 当前类拍满 → 下一类或结束
        if (step == BaselineStore.Kind.WITHOUT_MASK) {
            step = BaselineStore.Kind.WITH_MASK
            renderStep()
            showPreviewPhase()
            toast("不戴已够张数。接下来戴上面罩，多角度再拍。")
            return
        }
        finishWithSeparationCheck()
    }

    private fun tryFinishEarly() {
        if (!store.isComplete(DetectionConfig.BASELINE_MIN_PER_KIND)) {
            toast("每类至少再拍到 ${DetectionConfig.BASELINE_MIN_PER_KIND} 张。")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("张数够了，提前结束？")
            .setMessage("建议每类拍满 ${DetectionConfig.BASELINE_TARGET_PER_KIND} 张不同头位，识别更稳。现在也可以先用。")
            .setPositiveButton("提前完成") { _, _ -> finishWithSeparationCheck() }
            .setNegativeButton("继续拍", null)
            .show()
    }

    private fun finishWithSeparationCheck() {
        lifecycleScope.launch {
            binding.confirmButton.isEnabled = false
            binding.hintLabel.text = "正在看两类基准能不能分开…"
            val separation = try {
                withContext(Dispatchers.Default) { embeddingSeparation() }
            } catch (error: Exception) {
                toast("基准读不出来：${error.message ?: ""}")
                binding.confirmButton.isEnabled = true
                return@launch
            }
            binding.confirmButton.isEnabled = true
            if (separation != null && separation < DetectionConfig.EMBED_MIN_BASELINE_SEPARATION) {
                MaterialAlertDialogBuilder(this@CalibrationActivity)
                    .setTitle("两类看起来太像")
                    .setMessage("按现在的本机模型，戴/不戴不太分得开。建议多拍几个明显不同的头位，光线保持接近睡觉时。")
                    .setPositiveButton("仍然使用") { _, _ -> finishCalibration() }
                    .setNegativeButton("重拍") { _, _ -> restart() }
                    .show()
            } else {
                finishCalibration()
            }
        }
    }

    private fun embeddingSeparation(): Float? {
        val on = store.loadEmbeddings(BaselineStore.Kind.WITH_MASK)
        val off = store.loadEmbeddings(BaselineStore.Kind.WITHOUT_MASK)
        if (on.isEmpty() || off.isEmpty()) return null
        return EmbeddingClusterClassifier(on, off).clusterSeparation()
    }

    private fun finishCalibration() {
        settings.setCalibrationSkipped(false)
        toast("基准已保存在这台手机上。")
        finish()
    }

    private fun confirmSkip() {
        MaterialAlertDialogBuilder(this)
            .setTitle("跳过校准？")
            .setMessage("这是开发调试用的。跳过之后可以打开监测看相机和通知，但不会和基准比较，也不会因为面罩响铃。")
            .setPositiveButton("仍然跳过") { _, _ ->
                settings.setCalibrationSkipped(true)
                finish()
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun restart() {
        store.clear()
        discardPending()
        step = BaselineStore.Kind.WITHOUT_MASK
        renderStep()
        showPreviewPhase()
    }

    private fun discardPending() {
        pending?.preview?.let { if (!it.isRecycled) it.recycle() }
        pending?.file?.delete()
        pending = null
        binding.confirmImage.setImageDrawable(null)
    }

    private fun renderStep() {
        val target = DetectionConfig.BASELINE_TARGET_PER_KIND
        val without = store.count(BaselineStore.Kind.WITHOUT_MASK)
        val with = store.count(BaselineStore.Kind.WITH_MASK)
        binding.stepLabel.text = buildString {
            append(store.progressLabel(BaselineStore.Kind.WITHOUT_MASK, target))
            append(" · ")
            append(store.progressLabel(BaselineStore.Kind.WITH_MASK, target))
            append("\n当前：")
            append(if (step == BaselineStore.Kind.WITHOUT_MASK) "不戴面罩" else "戴着面罩")
            append("（再拍 ")
            append((target - store.count(step)).coerceAtLeast(0))
            append(" 张到目标）")
        }
        binding.hintLabel.text = hintFor(step)
        binding.restartButton.visibility = if (without + with > 0) View.VISIBLE else View.GONE
        binding.doneEarlyButton.visibility =
            if (store.isComplete(DetectionConfig.BASELINE_MIN_PER_KIND) &&
                (without < target || with < target)
            ) View.VISIBLE else View.GONE
    }

    private fun hintFor(kind: BaselineStore.Kind): String {
        return if (kind == BaselineStore.Kind.WITHOUT_MASK) {
            "后摄对准枕头/脸（可切前置）。不要戴面罩。多拍几个轻微不同的头位：正对、略左、略右。光线尽量接近睡觉时，可开微弱手电筒。"
        } else {
            "戴上平时睡觉的面罩，同样多角度拍几张。姿势和距离尽量接近真正入睡。"
        }
    }

    private fun showPreviewPhase() {
        binding.confirmImage.visibility = View.GONE
        binding.shutterButton.visibility = View.VISIBLE
        binding.countdownButton.visibility = View.VISIBLE
        binding.confirmButton.visibility = View.GONE
        binding.retakeButton.visibility = View.GONE
        binding.warningLabel.visibility = View.GONE
        setShootingEnabled(capture.isBound && !busy)
        renderStep()
    }

    private fun showConfirmPhase(shot: AnalyzedShot) {
        binding.confirmImage.setImageBitmap(shot.preview)
        binding.confirmImage.visibility = View.VISIBLE
        binding.shutterButton.visibility = View.GONE
        binding.countdownButton.visibility = View.GONE
        binding.countdown.visibility = View.GONE
        binding.confirmButton.visibility = View.VISIBLE
        binding.retakeButton.visibility = View.VISIBLE
        val warnings = buildList {
            if (shot.dark) add("这张几乎没有细节，太暗了。可开微弱手电筒或小夜灯再拍。")
            if (shot.embedding == null) add("本机模型暂时算不出特征，仍会保存照片；监测时可能走精度较差的 fallback。")
        }
        binding.warningLabel.visibility = if (warnings.isEmpty()) View.GONE else View.VISIBLE
        binding.warningLabel.text = warnings.joinToString("\n")
    }

    private fun setShootingEnabled(enabled: Boolean) {
        binding.shutterButton.isEnabled = enabled
        binding.countdownButton.isEnabled = enabled
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private data class AnalyzedShot(
        val file: File,
        val preview: Bitmap,
        val dark: Boolean,
        val embedding: FloatArray?,
    )
}
