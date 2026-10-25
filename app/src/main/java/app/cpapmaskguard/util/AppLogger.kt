package app.cpapmaskguard.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 持久应用内日志：写 filesDir/logs/app.log，同时同步到 android.util.Log。
 * 滚动约 768KB，保留一份 app.log.1。
 */
object AppLogger {
    private const val DIR_NAME = "logs"
    private const val FILE_NAME = "app.log"
    private const val BACKUP_NAME = "app.log.1"
    private const val MAX_BYTES = 768 * 1024L
    private const val DEFAULT_TAG = "CpapMaskGuard"

    private val lock = ReentrantLock()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var ready = false

    fun init(context: Context) {
        lock.withLock {
            if (ready) return
            val dir = File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }
            logDir = dir
            ready = true
        }
    }

    fun d(tag: String = DEFAULT_TAG, msg: String, throwable: Throwable? = null) =
        write('D', tag, msg, throwable)

    fun i(tag: String = DEFAULT_TAG, msg: String, throwable: Throwable? = null) =
        write('I', tag, msg, throwable)

    fun w(tag: String = DEFAULT_TAG, msg: String, throwable: Throwable? = null) =
        write('W', tag, msg, throwable)

    fun e(tag: String = DEFAULT_TAG, msg: String, throwable: Throwable? = null) =
        write('E', tag, msg, throwable)

    fun logFile(): File {
        val dir = logDir ?: error("AppLogger 尚未 init")
        return File(dir, FILE_NAME)
    }

    fun readTail(maxChars: Int = 80_000): String {
        lock.withLock {
            val dir = logDir ?: return "（日志尚未初始化）"
            val primary = File(dir, FILE_NAME)
            val backup = File(dir, BACKUP_NAME)
            val combined = StringBuilder()
            if (backup.exists()) {
                combined.append(backup.readTextSafe())
            }
            if (primary.exists()) {
                if (combined.isNotEmpty()) combined.append('\n')
                combined.append(primary.readTextSafe())
            }
            if (combined.isEmpty()) return "（暂无日志）"
            val text = combined.toString()
            return if (text.length <= maxChars) {
                text
            } else {
                "…（前面已截断）…\n" + text.takeLast(maxChars)
            }
        }
    }

    fun clear() {
        lock.withLock {
            val dir = logDir ?: return
            File(dir, FILE_NAME).delete()
            File(dir, BACKUP_NAME).delete()
        }
    }

    /** 可读错误摘要，便于 UI 状态文案（含 cause 链首条 message）。 */
    fun summarize(error: Throwable?, maxLen: Int = 160): String {
        if (error == null) return "未知错误"
        val parts = mutableListOf<String>()
        var cur: Throwable? = error
        var depth = 0
        while (cur != null && depth < 4) {
            val name = cur.javaClass.simpleName
            val msg = cur.message?.trim().orEmpty()
            parts += if (msg.isEmpty()) name else "$name: $msg"
            cur = cur.cause
            depth++
        }
        val joined = parts.joinToString(" ← ")
        return if (joined.length <= maxLen) joined else joined.take(maxLen - 1) + "…"
    }

    private fun write(level: Char, tag: String, msg: String, throwable: Throwable?) {
        when (level) {
            'D' -> if (throwable != null) Log.d(tag, msg, throwable) else Log.d(tag, msg)
            'I' -> if (throwable != null) Log.i(tag, msg, throwable) else Log.i(tag, msg)
            'W' -> if (throwable != null) Log.w(tag, msg, throwable) else Log.w(tag, msg)
            'E' -> if (throwable != null) Log.e(tag, msg, throwable) else Log.e(tag, msg)
        }
        if (!ready) return
        val stamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val header = "$stamp $level/$tag: $msg"
        val body = if (throwable != null) {
            header + "\n" + stackTraceOf(throwable)
        } else {
            header
        }
        lock.withLock {
            try {
                val dir = logDir ?: return
                val file = File(dir, FILE_NAME)
                if (file.exists() && file.length() >= MAX_BYTES) {
                    val backup = File(dir, BACKUP_NAME)
                    backup.delete()
                    file.renameTo(backup)
                }
                FileOutputStream(file, true).bufferedWriter().use { out ->
                    out.append(body)
                    out.append('\n')
                }
            } catch (_: Throwable) {
                // 写文件失败不影响主流程
            }
        }
    }

    private fun stackTraceOf(error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }

    private fun File.readTextSafe(): String =
        try {
            readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
}
