package app.cpapmaskguard.capture

import android.content.Context
import android.os.Build
import app.cpapmaskguard.service.MonitorService
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 开始守护前的取景。和监测服务共用进程里的 CameraX，
 * 点开始之前必须先 [stop]，否则会把服务的相机会话拆掉。
 */
object AimPreviewTarget {
    @Volatile
    var view: PreviewView? = null
}

class AimPreview(
    private val context: Context,
    private val owner: LifecycleOwner,
) {
    private val gate = Mutex()
    private var provider: ProcessCameraProvider? = null

    suspend fun start(view: PreviewView, useFront: Boolean, torch: Boolean, level: Float) {
        gate.withLock {
            val cameraProvider = provider ?: ProcessCameraProvider.getInstance(context).await(context).also {
                provider = it
            }
            val selector = if (useFront) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            if (!cameraProvider.hasCamera(selector)) return
            if (MonitorService.alive.get()) return
            cameraProvider.unbindAll()
            if (MonitorService.alive.get()) return
            view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            val preview = Preview.Builder().build()
            val rotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
            preview.targetRotation = rotation
            preview.setSurfaceProvider(view.surfaceProvider)
            val camera = cameraProvider.bindToLifecycle(owner, selector, preview)
            if (torch && !useFront && camera.cameraInfo.hasFlashUnit()) {
                applyDim(camera, level)
                camera.cameraControl.enableTorch(true)
            }
        }
    }

    /** 能调亮度就用档位；只能开关的机器保持 enableTorch，不把灯关掉。 */
    private fun applyDim(camera: androidx.camera.core.Camera, level: Float) {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            val info = camera.cameraInfo
            val control = camera.cameraControl
            val maxMethod = info.javaClass.methods.firstOrNull {
                it.name == "getMaxTorchStrengthLevel" && it.parameterTypes.isEmpty()
            }
            val setMethod = control.javaClass.methods.firstOrNull {
                it.name == "setTorchStrengthLevel" && it.parameterTypes.size == 1
            }
            val max = (maxMethod?.invoke(info) as? Int) ?: 0
            if (setMethod != null && max > 1) {
                val strength = (1 + (level.coerceIn(0.01f, 1f) * (max - 1)).toInt()).coerceIn(1, max)
                setMethod.invoke(control, strength)
            }
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        try {
            provider?.unbindAll()
        } catch (_: RuntimeException) {
        }
    }
}
