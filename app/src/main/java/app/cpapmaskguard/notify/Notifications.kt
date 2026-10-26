package app.cpapmaskguard.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import app.cpapmaskguard.AlarmActivity
import app.cpapmaskguard.FillLightActivity
import app.cpapmaskguard.MainActivity
import app.cpapmaskguard.settings.FillLightMode
import app.cpapmaskguard.settings.SettingsStore
import app.cpapmaskguard.R
import app.cpapmaskguard.service.BurnInService
import app.cpapmaskguard.service.MonitorService

object Notifications {
    const val ID_MONITOR = 1001
    const val ID_ALARM = 1002
    const val ID_BURN_IN = 1003
    const val ID_GUARD = 1004
    private const val CHANNEL_MONITOR = "monitor"
    private const val CHANNEL_ALARM = "alarm"
    private const val CHANNEL_GUARD = "guard_failure"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val monitor = NotificationChannel(
            CHANNEL_MONITOR,
            "监测",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "睡眠监测进行中。这条通知是为了让系统别把相机清掉。"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        val alarm = NotificationChannel(
            CHANNEL_ALARM,
            "脱落提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "面罩可能脱落时的全屏提醒。铃声由应用用闹钟音量播放。"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val guardSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val guard = NotificationChannel(
            CHANNEL_GUARD,
            "守护失效",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "监测中断、连续判定失败或长时间没有新结果。提示音和脱落铃声不是同一个。"
            setSound(
                guardSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(monitor)
        manager.createNotificationChannel(alarm)
        manager.createNotificationChannel(guard)
    }

    fun monitoring(context: Context, text: String): Notification {
        val front = SettingsStore(context).settings().fillLightMode == FillLightMode.FRONT_SCREEN
        val tapClass = if (front) FillLightActivity::class.java else MainActivity::class.java
        val body = if (front) {
            "$text（前摄屏幕补光：点通知可回到白屏）"
        } else {
            text
        }
        return NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("面罩守护监测中")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(activityIntent(context, tapClass, 1))
            .addAction(0, "停止监测", serviceIntent(context, MonitorService.ACTION_STOP, 2))
            .build()
    }

    fun alarm(context: Context): Notification {
        val openAlarm = activityIntent(context, AlarmActivity::class.java, 3)
        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("面罩可能已经脱落")
            .setContentText("辅助提醒。点一下可以停止铃声。")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(openAlarm, true)
            .setContentIntent(openAlarm)
            .addAction(0, "停止提醒", serviceIntent(context, MonitorService.ACTION_STOP_ALARM, 4))
            .build()
    }


    fun burnIn(context: Context, text: String): Notification {
        return NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("面罩守护 · 使用前烤机")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(activityIntent(context, MainActivity::class.java, 11))
            .addAction(0, "取消烤机", burnInServiceIntent(context, BurnInService.ACTION_CANCEL, 12))
            .build()
    }

    /**
     * 守护失效。用通知音，不用脱落那条循环闹钟。
     * 相机绑定失败时标题固定为「守护已中断，点此恢复」，并尽量全屏拉回主界面。
     */
    fun guardFailure(context: Context, title: String, body: String, fullScreen: Boolean): Notification {
        val open = activityIntent(context, MainActivity::class.java, 21)
        val builder = NotificationCompat.Builder(context, CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (fullScreen) {
            builder.setFullScreenIntent(open, true)
        }
        return builder.build()
    }

    fun notifyGuardFailure(context: Context, title: String, body: String, fullScreen: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(ID_GUARD, guardFailure(context, title, body, fullScreen))
        chirp(context)
    }

    /** 通知渠道在前台时可能不出声，再补一声短提示，仍然不是脱落铃声。 */
    fun chirp(context: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.play()
        } catch (_: Exception) {
        }
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(280, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    fun cancelAlarm(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_ALARM)
    }

    private fun activityIntent(context: Context, clazz: Class<*>, request: Int): PendingIntent {
        val intent = Intent(context, clazz).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            request,
            intent,
            pendingFlags(),
        )
    }


    private fun burnInServiceIntent(context: Context, action: String, request: Int): PendingIntent {
        val intent = Intent(context, BurnInService::class.java).setAction(action)
        return PendingIntent.getService(context, request, intent, pendingFlags())
    }

    private fun serviceIntent(context: Context, action: String, request: Int): PendingIntent {
        val intent = Intent(context, MonitorService::class.java).setAction(action)
        return PendingIntent.getService(context, request, intent, pendingFlags())
    }

    private fun pendingFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
