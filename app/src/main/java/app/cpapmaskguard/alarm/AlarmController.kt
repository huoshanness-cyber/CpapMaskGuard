package app.cpapmaskguard.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import app.cpapmaskguard.LOG_TAG
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * 闹钟级循环铃声 + 强震动。只在提醒期间把闹钟音量拉到最大，停止后恢复。
 * 优先用系统闹钟铃声；没有或播不出来时，改用一段自己生成的方波。
 */
class AlarmController(private val context: Context) {
    private val lock = Any()
    private var player: MediaPlayer? = null
    private var synthThread: Thread? = null
    @Volatile private var synthRunning = false
    private var vibrator: Vibrator? = null
    private var previousAlarmVolume: Int? = null
    private var focusRequest: AudioFocusRequest? = null
    private var screenWake: PowerManager.WakeLock? = null

    var isPlaying: Boolean = false
        private set

    fun start() {
        synchronized(lock) {
            if (isPlaying) return
            isPlaying = true
            boostAlarmVolume()
            requestFocus()
            turnScreenOn()
            if (!startRingtone()) startSynth()
            startVibration()
        }
    }

    fun stop() {
        synchronized(lock) {
            synthRunning = false
            synthThread?.interrupt()
            synthThread = null
            player?.run {
                try {
                    stop()
                } catch (_: IllegalStateException) {
                }
                release()
            }
            player = null
            vibrator?.cancel()
            vibrator = null
            if (screenWake?.isHeld == true) screenWake?.release()
            screenWake = null
            abandonFocus()
            restoreAlarmVolume()
            isPlaying = false
        }
    }

    private fun alarmAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun boostAlarmVolume() {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        previousAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        } catch (error: SecurityException) {
            Log.w(LOG_TAG, "不能修改闹钟音量", error)
        }
    }

    private fun restoreAlarmVolume() {
        val previous = previousAlarmVolume ?: return
        previousAlarmVolume = null
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0)
        } catch (error: SecurityException) {
            Log.w(LOG_TAG, "不能恢复闹钟音量", error)
        }
    }

    private fun requestFocus() {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(alarmAttributes())
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = request
        audio.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
    }

    private fun startRingtone(): Boolean {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return false
        return try {
            val media = MediaPlayer()
            media.setDataSource(context, uri)
            media.setAudioAttributes(alarmAttributes())
            media.isLooping = true
            media.setVolume(1f, 1f)
            media.prepare()
            media.start()
            player = media
            true
        } catch (error: Exception) {
            Log.w(LOG_TAG, "系统闹钟铃声播不出来", error)
            player?.release()
            player = null
            false
        }
    }

    private fun startSynth() {
        val sampleRate = 44100
        val bufferSize = android.media.AudioTrack.getMinBufferSize(
            sampleRate,
            android.media.AudioFormat.CHANNEL_OUT_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 5)
        val track = android.media.AudioTrack.Builder()
            .setAudioAttributes(alarmAttributes())
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(android.media.AudioTrack.MODE_STREAM)
            .build()
        synthRunning = true
        track.play()
        synthThread = thread(name = "mask-alarm-synth", isDaemon = true) {
            val buffer = ShortArray(bufferSize)
            var phase = 0.0
            var samplesInSegment = 0
            val segment = sampleRate / 2
            var frequency = 880.0
            try {
                while (synthRunning && !Thread.currentThread().isInterrupted) {
                    for (index in buffer.indices) {
                        val sample = if (sin(phase) >= 0) 0.9 else -0.9
                        buffer[index] = (sample * Short.MAX_VALUE).toInt().toShort()
                        phase += 2.0 * PI * frequency / sampleRate
                        samplesInSegment += 1
                        if (samplesInSegment >= segment) {
                            samplesInSegment = 0
                            frequency = if (frequency < 900) 1320.0 else 880.0
                        }
                    }
                    track.write(buffer, 0, buffer.size)
                }
            } catch (_: InterruptedException) {
            } finally {
                track.pause()
                track.flush()
                track.release()
            }
        }
    }

    private fun startVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return
        val timings = longArrayOf(0, 700, 300, 700, 300, 1000)
        val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, amplitudes, 0)
        } else {
            VibrationEffect.createWaveform(timings, 0)
        }
        vibrator.vibrate(effect)
        this.vibrator = vibrator
    }

    @Suppress("DEPRECATION")
    private fun turnScreenOn() {
        try {
            val power = context.getSystemService(PowerManager::class.java) ?: return
            val wake = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "app.cpapmaskguard:alarm",
            )
            wake.acquire(10 * 60 * 1000L)
            screenWake = wake
        } catch (error: RuntimeException) {
            Log.w(LOG_TAG, "点不亮屏幕", error)
        }
    }
}
