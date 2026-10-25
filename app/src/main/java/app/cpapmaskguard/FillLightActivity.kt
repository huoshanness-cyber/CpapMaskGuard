package app.cpapmaskguard

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.cpapmaskguard.databinding.ActivityFillLightBinding
import app.cpapmaskguard.runtime.MonitorRuntime
import app.cpapmaskguard.runtime.RunStatus
import app.cpapmaskguard.service.MonitorService
import app.cpapmaskguard.settings.FillLightMode
import app.cpapmaskguard.settings.SettingsStore
import app.cpapmaskguard.util.AppLogger
import kotlinx.coroutines.launch

/**
 * 前摄白屏补光页：全白背景 + 窗口亮度滑条 + 停止监测。
 * 监测在 [MonitorService]；本页只负责把屏幕当灯，并保持常亮。
 */
class FillLightActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFillLightBinding
    private lateinit var settings: SettingsStore
    private var rendering = false
    private var uiReady = false
    private var savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        if (settings.settings().fillLightMode != FillLightMode.FRONT_SCREEN) {
            AppLogger.i(LOG_TAG, "FillLightActivity：当前非前摄白屏模式，关闭")
            finish()
            return
        }
        binding = ActivityFillLightBinding.inflate(layoutInflater)
        setContentView(binding.root)
        uiReady = true

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        savedBrightness = window.attributes.screenBrightness
        applyWindowBrightness(settings.settings().screenBrightness)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    Toast.makeText(this@FillLightActivity, R.string.fill_light_back_hint, Toast.LENGTH_SHORT).show()
                    finish()
                }
            },
        )
        binding.stopButton.setOnClickListener {
            MonitorService.send(this, MonitorService.ACTION_STOP)
            finish()
        }
        binding.brightnessSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || rendering) return@addOnChangeListener
            val level = value.coerceIn(0.05f, 1f)
            settings.setScreenBrightness(level)
            applyWindowBrightness(level)
            binding.brightnessLabel.text = getString(R.string.screen_brightness_level, (level * 100).toInt())
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MonitorRuntime.snapshot.collect { snap ->
                    binding.statusLine.text = "${snap.headline}\n${snap.detail}"
                    val running = when (snap.status) {
                        RunStatus.MONITORING, RunStatus.STARTING, RunStatus.ALARMING -> true
                        RunStatus.ERROR -> MonitorService.alive.get()
                        RunStatus.STOPPED -> false
                    }
                    if (!running && snap.status != RunStatus.STARTING) {
                        // 用户已停或失败：关掉白屏页，避免空占电
                        if (snap.status == RunStatus.STOPPED || snap.status == RunStatus.ERROR) {
                            finish()
                        }
                    }
                }
            }
        }
        AppLogger.i(LOG_TAG, "FillLightActivity 已开，窗口亮度=${settings.settings().screenBrightness}")
    }

    override fun onResume() {
        super.onResume()
        if (!uiReady) return
        if (settings.settings().fillLightMode != FillLightMode.FRONT_SCREEN) {
            finish()
            return
        }
        rendering = true
        val level = settings.settings().screenBrightness
        binding.brightnessSlider.value = level.coerceIn(0.05f, 1f)
        binding.brightnessLabel.text = getString(R.string.screen_brightness_level, (level * 100).toInt())
        applyWindowBrightness(level)
        rendering = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        // 恢复窗口亮度覆盖（不改系统全局亮度）
        try {
            val lp = window.attributes
            lp.screenBrightness = savedBrightness
            window.attributes = lp
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // 返回不自动停监测：只关白屏；用户可用停止按钮或通知

    private fun applyWindowBrightness(level: Float) {
        val lp = window.attributes
        lp.screenBrightness = level.coerceIn(0.05f, 1f)
        window.attributes = lp
    }

    companion object {
        fun launchIfNeeded(context: android.content.Context) {
            val store = SettingsStore(context)
            if (store.settings().fillLightMode != FillLightMode.FRONT_SCREEN) return
            val intent = Intent(context, FillLightActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(intent)
        }
    }
}
