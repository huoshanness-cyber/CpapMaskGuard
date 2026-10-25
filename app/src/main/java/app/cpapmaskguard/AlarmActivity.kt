package app.cpapmaskguard

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import app.cpapmaskguard.databinding.ActivityAlarmBinding
import app.cpapmaskguard.runtime.MonitorRuntime
import app.cpapmaskguard.runtime.RunStatus
import app.cpapmaskguard.service.MonitorService

/** 锁屏上的停止页。铃声本身在 [MonitorService] 里播。 */
class AlarmActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val alarming = MonitorRuntime.snapshot.value.status == RunStatus.ALARMING
        if (!alarming) {
            binding.alarmTitle.text = "现在没有提醒"
            binding.alarmBody.text = "监测如果还开着，回到主界面可以看状态。"
            binding.stopButton.text = "关闭"
        }
        binding.stopButton.setOnClickListener {
            MonitorService.send(this, MonitorService.ACTION_STOP_ALARM)
            finish()
        }
    }
}
