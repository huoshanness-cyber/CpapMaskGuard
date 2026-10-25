package app.cpapmaskguard.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.cpapmaskguard.LOG_TAG
import app.cpapmaskguard.core.SnoreHeuristics
import app.cpapmaskguard.util.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 监测期间本地听鼾：16 kHz / mono / 短缓冲，间歇分析，不写盘、不联网。
 * 无 RECORD_AUDIO 时 [start] 直接失败，相机监测可继续。
 */
class SnoreListener(private val context: Context) {
    private val tracker = SnoreHeuristics.Tracker()
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var record: AudioRecord? = null

    @Volatile
    var lastHint: String = "听声未开"
        private set

    @Volatile
    var lastRms: Float = 0f
        private set

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun isConfirmed(): Boolean = tracker.confirmed

    fun confirmedForMs(): Long = tracker.confirmedSinceElapsedMs

    fun start(): Boolean {
        if (!hasPermission()) {
            lastHint = "无麦克风权限"
            AppLogger.w(LOG_TAG, "SnoreListener: 无 RECORD_AUDIO，跳过听鼾")
            return false
        }
        if (running.getAndSet(true)) return true
        tracker.reset()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            running.set(false)
            lastHint = "麦克风不可用"
            AppLogger.w(LOG_TAG, "SnoreListener: getMinBufferSize=$minBuf")
            return false
        }
        val bufSize = (minBuf * 2).coerceAtLeast(SAMPLE_RATE) // ~0.5–1s
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufSize,
            )
        } catch (error: Exception) {
            running.set(false)
            lastHint = "麦克风打开失败"
            AppLogger.e(LOG_TAG, "SnoreListener: AudioRecord 创建失败", error)
            return false
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            running.set(false)
            lastHint = "麦克风未初始化"
            AppLogger.w(LOG_TAG, "SnoreListener: AudioRecord 未初始化")
            return false
        }
        record = audioRecord
        try {
            audioRecord.startRecording()
        } catch (error: Exception) {
            audioRecord.release()
            record = null
            running.set(false)
            lastHint = "无法开始录音"
            AppLogger.e(LOG_TAG, "SnoreListener: startRecording 失败", error)
            return false
        }
        lastHint = "听声中"
        AppLogger.i(LOG_TAG, "SnoreListener: 开始 16kHz 听鼾 buf=$bufSize")
        worker = thread(name = "snore-listen", isDaemon = true) {
            val window = ShortArray(WINDOW_SAMPLES)
            var filled = 0
            val scratch = ShortArray(minBuf.coerceAtLeast(1024))
            var windows = 0
            while (running.get()) {
                val n = try {
                    audioRecord.read(scratch, 0, scratch.size)
                } catch (_: Exception) {
                    break
                }
                if (n <= 0) {
                    try {
                        Thread.sleep(40)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                var off = 0
                while (off < n && running.get()) {
                    val copy = minOf(WINDOW_SAMPLES - filled, n - off)
                    System.arraycopy(scratch, off, window, filled, copy)
                    filled += copy
                    off += copy
                    if (filled >= WINDOW_SAMPLES) {
                        val feat = SnoreHeuristics.analyze(window, WINDOW_SAMPLES)
                        lastRms = feat.rms
                        val now = SystemClock.elapsedRealtime()
                        tracker.onWindow(feat.snoreLike, now)
                        lastHint = tracker.hint() +
                            " · rms=${"%.3f".format(feat.rms)} zcr=${"%.2f".format(feat.zcr)}"
                        windows++
                        if (windows % 8 == 0) {
                            AppLogger.i(
                                LOG_TAG,
                                "听鼾 #$windows like=${feat.snoreLike} conf=${tracker.confirmed} ${lastHint}",
                            )
                        }
                        filled = 0
                        // 省电：每窗分析后歇一小会儿，而不是打满 CPU
                        try {
                            Thread.sleep(ANALYSIS_PAUSE_MS)
                        } catch (_: InterruptedException) {
                            running.set(false)
                            break
                        }
                    }
                }
            }
            AppLogger.i(LOG_TAG, "SnoreListener: 线程结束 windows=$windows")
        }
        return true
    }

    fun stop() {
        running.set(false)
        try {
            worker?.join(800)
        } catch (_: Exception) {
        }
        worker = null
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        tracker.reset()
        lastHint = "听声已停"
        lastRms = 0f
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        /** ~0.5s 窗 @ 16kHz */
        private const val WINDOW_SAMPLES = 8_000
        private const val ANALYSIS_PAUSE_MS = 200L
    }
}
