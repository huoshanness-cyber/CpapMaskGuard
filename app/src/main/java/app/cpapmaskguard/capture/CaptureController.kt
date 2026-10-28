package app.cpapmaskguard.capture

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.SystemClock
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import app.cpapmaskguard.LOG_TAG
import app.cpapmaskguard.util.AppLogger
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * 按补光模式绑定前摄或后摄拍照。
 * 监测服务里不打开预览，用一条直接丢掉的分析流维持测光；校准页绑 [PreviewView]。
 * 前摄模式：手电筒强制关，由白屏 Activity 补光。
 * 后摄自备光源：默认关手电筒；可选高级弱光常亮。
 */
class CaptureController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var boundCamera: Camera? = null

    var nightExtension: Boolean = false
        private set

    var isBound: Boolean = false
        private set

    var previewBound: Boolean = false
        private set

    var boundWidth: Int = 0
        private set

    var boundHeight: Int = 0
        private set

    var usingFront: Boolean = false
        private set

    var torchSupported: Boolean = false
        private set

    /** 0..1，映射到设备 torch strength（API 33+）；更低版本只能开/关。 */
    var torchLevel: Float = CameraTuning.DEFAULT_TORCH_LEVEL
        private set

    var torchEnabled: Boolean = false
        private set

    suspend fun bind(
        width: Int,
        height: Int,
        previewView: PreviewView?,
        useFrontCamera: Boolean = false,
        enableTorch: Boolean = false,
        torchLevel: Float = CameraTuning.DEFAULT_TORCH_LEVEL,
        /**
         * true：偏好镜头不可用则直接失败（前摄白屏模式用，禁止静默改后摄）。
         * false：可回退到另一只（仅兼容旧调用）。
         */
        requirePreferredLens: Boolean = true,
    ) {
        val cameraProvider = provider ?: ProcessCameraProvider.getInstance(context).await(context).also {
            provider = it
        }
        var useFront = useFrontCamera
        var preferred = if (useFront) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        if (!cameraProvider.hasCamera(preferred)) {
            val want = if (useFront) "前摄" else "后摄"
            if (requirePreferredLens) {
                AppLogger.e(LOG_TAG, "偏好摄像头不可用且禁止回退 cam=$want")
                error("打不开${want}：这台手机没有可用的${want}摄像头")
            }
            val other = !useFront
            val fallback = if (other) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            if (!cameraProvider.hasCamera(fallback)) {
                error("这台手机没有可用摄像头")
            }
            AppLogger.w(LOG_TAG, "偏好摄像头不可用，改用另一只（allowFallback）")
            useFront = other
            preferred = fallback
        }
        unbind()
        sweepTemp()
        val nightSelector = tryEnableNight(cameraProvider, preferred)
        val enableTorchEffective = enableTorch && !useFront
        val attempts = buildList {
            if (nightSelector != null) {
                add(Attempt(nightSelector, lowLightHints = false, night = true, label = "夜景扩展"))
            }
            add(Attempt(preferred, lowLightHints = true, night = false, label = "普通相机加夜景参数"))
            add(Attempt(preferred, lowLightHints = false, night = false, label = "普通相机"))
        }
        var lastError: Exception? = null
        for (attempt in attempts) {
            try {
                bindWith(cameraProvider, attempt, width, height, previewView)
                nightExtension = attempt.night
                boundWidth = width
                boundHeight = height
                usingFront = useFront
                isBound = true
                previewBound = previewView != null
                this.torchLevel = torchLevel.coerceIn(0f, 1f)
                applyTorch(enableTorchEffective, this.torchLevel)
                AppLogger.i(
                    LOG_TAG,
                    "相机绑定成功 attempt=${attempt.label} cam=${if (useFront) "front" else "back"} "
                        + "${width}×${height} torch=${enableTorchEffective} level=${this.torchLevel}",
                )
                return
            } catch (error: Exception) {
                lastError = error
                AppLogger.e(LOG_TAG, "相机绑定失败 attempt=${attempt.label} cam=${if (useFront) "front" else "back"} ${width}×${height}", error)
                unbind()
            }
        }
        val finalError = lastError ?: IllegalStateException("相机绑定失败")
        AppLogger.e(
            LOG_TAG,
            "相机所有绑定尝试均失败 cam=${if (useFront) "front" else "back"} ${width}×${height}",
            finalError,
        )
        throw finalError
    }

    suspend fun setTorch(enabled: Boolean, level: Float = torchLevel) {
        torchLevel = level.coerceIn(0f, 1f)
        applyTorch(enabled, torchLevel)
    }

    suspend fun takePicture(): File {
        val capture = imageCapture ?: error("相机还没打开")
        capture.targetRotation = currentRotation()
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "${SystemClock.elapsedRealtime()}.jpg")
        return withTimeout(CameraTuning.CAPTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val options = ImageCapture.OutputFileOptions.Builder(file)
                    .setMetadata(
                        ImageCapture.Metadata().apply {
                            isReversedHorizontal = false
                        },
                    )
                    .build()
                capture.takePicture(
                    options,
                    cameraExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            if (cont.isActive) cont.resume(file)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            file.delete()
                            if (cont.isActive) cont.resumeWithException(exception)
                        }
                    },
                )
                cont.invokeOnCancellation { file.delete() }
            }
        }
    }

    fun unbind() {
        try {
            boundCamera?.cameraControl?.enableTorch(false)
        } catch (_: Throwable) {
        }
        try {
            provider?.unbindAll()
        } catch (error: RuntimeException) {
            AppLogger.w(LOG_TAG, "解绑相机失败", error)
        }
        imageCapture = null
        boundCamera = null
        isBound = false
        previewBound = false
        torchSupported = false
        torchBrightnessSupported = false
        loggedTorchBrightnessLimit = false
        torchEnabled = false
    }

    fun close() {
        unbind()
        cameraExecutor.shutdown()
    }

    fun sweepTemp() {
        File(context.cacheDir, "capture").listFiles()?.forEach { it.delete() }
    }

    /**
     * 是否支持手电筒亮度档（CameraX getMaxTorchStrengthLevel > 1）。
     * 红米 Note 12 5G (sunstone) 实测 max=1，仅开关。
     */
    var torchBrightnessSupported: Boolean = false
        private set

    private var loggedTorchBrightnessLimit: Boolean = false

    private suspend fun applyTorch(enabled: Boolean, level: Float) {
        val camera = boundCamera ?: return
        val info = camera.cameraInfo
        torchSupported = info.hasFlashUnit()
        if (!torchSupported) {
            torchEnabled = false
            torchBrightnessSupported = false
            AppLogger.w(LOG_TAG, "手电筒不可用（无闪光灯单元） enabled请求=$enabled")
            return
        }
        val control: CameraControl = camera.cameraControl
        try {
            // API 33+：max>1 才有亮度档；max<=1 表示机型只有开关。
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    val maxMethod = info.javaClass.methods.firstOrNull {
                        it.name == "getMaxTorchStrengthLevel" && it.parameterTypes.isEmpty()
                    }
                    val setMethod = control.javaClass.methods.firstOrNull {
                        it.name == "setTorchStrengthLevel" && it.parameterTypes.size == 1
                    }
                    val max = (maxMethod?.invoke(info) as? Int) ?: 0
                    torchBrightnessSupported = setMethod != null && max > 1
                    if (torchBrightnessSupported) {
                        val strength = (1 + (level * (max - 1)).toInt()).coerceIn(1, max)
                        setMethod!!.invoke(control, strength)
                        AppLogger.i(LOG_TAG, "torch strength=$strength / max=$max (level=$level)")
                    } else if (!loggedTorchBrightnessLimit) {
                        loggedTorchBrightnessLimit = true
                        AppLogger.i(
                            LOG_TAG,
                            "本机不支持手电筒亮度 maxTorchStrength=$max（仅开关，滑条无效）",
                        )
                    }
                } catch (error: Throwable) {
                    torchBrightnessSupported = false
                    AppLogger.w(LOG_TAG, "setTorchStrengthLevel 不可用", error)
                    AppLogger.i(LOG_TAG, "本机不支持手电筒亮度（反射失败，仅开关）")
                }
            } else {
                torchBrightnessSupported = false
                AppLogger.i(LOG_TAG, "本机不支持手电筒亮度（API<33，仅开关）")
            }
            // 必须等到 enableTorch 完成；否则小米上常出现「日志写了开、灯其实还没亮」
            control.enableTorch(enabled).await(context)
            torchEnabled = enabled
            AppLogger.i(
                LOG_TAG,
                "手电筒 ${if (enabled) "开" else "关"} level=$level " +
                    "brightnessAdjustable=$torchBrightnessSupported await=ok",
            )
        } catch (error: Throwable) {
            AppLogger.w(LOG_TAG, "手电筒控制失败 enabled=$enabled", error)
            torchEnabled = false
        }
    }

    private suspend fun tryEnableNight(
        cameraProvider: ProcessCameraProvider,
        selector: CameraSelector,
    ): CameraSelector? {
        return try {
            withTimeout(8_000) {
                val manager = ExtensionsManager.getInstanceAsync(context, cameraProvider).await(context)
                if (manager.isExtensionAvailable(selector, ExtensionMode.NIGHT)) {
                    AppLogger.i(LOG_TAG, "这台手机有 CameraX 夜景扩展")
                    manager.getExtensionEnabledCameraSelector(selector, ExtensionMode.NIGHT)
                } else {
                    AppLogger.i(LOG_TAG, "这台手机没有 CameraX 夜景扩展")
                    null
                }
            }
        } catch (error: TimeoutCancellationException) {
            AppLogger.w(LOG_TAG, "夜景扩展查询超时", error)
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.w(LOG_TAG, "夜景扩展查询失败", error)
            null
        }
    }

    private fun bindWith(
        cameraProvider: ProcessCameraProvider,
        attempt: Attempt,
        width: Int,
        height: Int,
        previewView: PreviewView?,
    ) {
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(CameraTuning.JPEG_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setResolutionSelector(resolutionOf(width, height))
        if (attempt.lowLightHints) {
            applyLowLightHints(captureBuilder)
        }
        val capture = captureBuilder.build()
        val rotation = currentRotation()
        capture.targetRotation = rotation

        val camera = if (previewView != null) {
            previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            val preview = Preview.Builder()
                .setResolutionSelector(resolutionOf(width, height))
                .build()
            preview.targetRotation = rotation
            preview.setSurfaceProvider(previewView.surfaceProvider)
            cameraProvider.bindToLifecycle(lifecycleOwner, attempt.selector, preview, capture)
        } else {
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setResolutionSelector(resolutionOf(640, 480))
                .build()
            analysis.targetRotation = rotation
            analysis.setAnalyzer(cameraExecutor) { image -> image.close() }
            cameraProvider.bindToLifecycle(lifecycleOwner, attempt.selector, analysis, capture)
        }
        imageCapture = capture
        boundCamera = camera
        applyExposure(camera)
    }

    private fun applyLowLightHints(builder: ImageCapture.Builder) {
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_USE_SCENE_MODE,
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_SCENE_MODE,
                CaptureRequest.CONTROL_SCENE_MODE_NIGHT,
            )
            .setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            )
            .setCaptureRequestOption(
                CaptureRequest.EDGE_MODE,
                CaptureRequest.EDGE_MODE_OFF,
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(5, 15),
            )
    }

    private fun applyExposure(camera: Camera) {
        val state = camera.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val range = state.exposureCompensationRange
        val index = CameraTuning.EXPOSURE_COMPENSATION_STEPS.coerceIn(range.lower, range.upper)
        if (index == 0) return
        camera.cameraControl.setExposureCompensationIndex(index)
        AppLogger.i(LOG_TAG, "曝光补偿设为 $index（机器范围 ${range.lower}..${range.upper}）")
    }

    private fun resolutionOf(width: Int, height: Int): ResolutionSelector {
        return ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(width, height),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()
    }

    private fun currentRotation(): Int {
        // 监测走 Service Context：API 30+ 上 context.display / getDisplay 会抛
        // UnsupportedOperationException。一律走 DisplayManager，失败则 ROTATION_0。
        return try {
            val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation
                ?: android.view.Surface.ROTATION_0
        } catch (_: Throwable) {
            android.view.Surface.ROTATION_0
        }
    }

    private data class Attempt(
        val selector: CameraSelector,
        val lowLightHints: Boolean,
        val night: Boolean,
        val label: String,
    )
}

