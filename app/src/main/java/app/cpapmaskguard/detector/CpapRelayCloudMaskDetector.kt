package app.cpapmaskguard.detector

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import app.cpapmaskguard.LOG_TAG
import app.cpapmaskguard.core.CloudUsage
import app.cpapmaskguard.core.FrameVote
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Official cloud path via https://cpap.njxxzf.cn — no MiniMax key on device.
 * Flow: GET /v1/config → pepper_hint → POST /v1/auth/device → POST /v1/classify (Bearer).
 */
class CpapRelayCloudMaskDetector(
    context: Context,
    private val baseUrl: String = DEFAULT_BASE,
    private val appVersion: String,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : CloudMaskDetector {
    private val appContext = context.applicationContext

    override val configured: Boolean =
        baseUrl.isNotBlank() &&
            baseUrl.trim().lowercase() !in OFF_VALUES

    private val root: String = baseUrl.trim().trimEnd('/')

    @Volatile private var accessToken: String? = null
    @Volatile private var tokenExpiresAtMs: Long = 0L
    @Volatile private var pepperHint: String? = null
    private val lock = Any()

    override suspend fun classify(jpegBytes: ByteArray): CloudMaskDetector.Result {
        if (!configured) {
            throw IllegalStateException("CPAP relay 未配置")
        }
        if (jpegBytes.isEmpty()) {
            throw IllegalArgumentException("空 JPEG")
        }
        return withContext(Dispatchers.IO) {
            withTimeout(timeoutMs) {
                try {
                    classifyOnce(jpegBytes, allowRetry = true)
                } catch (e: UnauthorizedException) {
                    clearToken()
                    classifyOnce(jpegBytes, allowRetry = false)
                }
            }
        }
    }

    private fun classifyOnce(jpegBytes: ByteArray, allowRetry: Boolean): CloudMaskDetector.Result {
        val token = ensureToken()
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val body = JSONObject()
            .put("schema", "cpap.classify.v1")
            .put("captured_at", System.currentTimeMillis())
            .put(
                "image",
                JSONObject()
                    .put("encoding", "jpeg_base64")
                    .put("data", b64)
                    .put("max_side", 640)
                    .put("jpeg_quality", 55)
                    .put("exif_stripped", true),
            )
            .toString()

        val conn = open("POST", "/v1/classify", readTimeoutMs = READ_CLASSIFY_MS).apply {
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doOutput = true
        }
        try {
            writeBody(conn, body)
            val code = conn.responseCode
            val raw = readBody(conn)
            if (code == 401 && allowRetry) {
                throw UnauthorizedException(clip(raw))
            }
            if (code !in 200..299) {
                throw IllegalStateException("relay classify HTTP $code: ${clip(raw)}")
            }
            return parseClassify(raw)
        } finally {
            conn.disconnect()
        }
    }

    private fun ensureToken(): String {
        synchronized(lock) {
            val cached = accessToken
            if (cached != null && tokenExpiresAtMs > System.currentTimeMillis() + 60_000L) {
                return cached
            }
            val hint = fetchPepperHintLocked()
            pepperHint = hint
            val hash = deviceIdHash(hint)
            val body = JSONObject()
                .put("device_id_hash", hash)
                .put("platform", "android")
                .put("app_version", appVersion)
                .toString()
            val conn = open("POST", "/v1/auth/device", readTimeoutMs = READ_AUTH_MS).apply {
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
            }
            try {
                writeBody(conn, body)
                val code = conn.responseCode
                val raw = readBody(conn)
                if (code !in 200..299) {
                    throw IllegalStateException("relay auth HTTP $code: ${clip(raw)}")
                }
                val json = JSONObject(raw)
                val token = json.optString("access_token", "")
                if (token.isBlank()) {
                    throw IllegalStateException("relay auth 无 access_token")
                }
                val expiresIn = json.optInt("expires_in", 3600).coerceAtLeast(60)
                accessToken = token
                tokenExpiresAtMs = System.currentTimeMillis() + expiresIn * 1000L
                return token
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun fetchPepperHintLocked(): String {
        val cached = pepperHint
        if (!cached.isNullOrBlank()) return cached
        val conn = open("GET", "/v1/config", readTimeoutMs = READ_AUTH_MS)
        try {
            val code = conn.responseCode
            val raw = readBody(conn)
            if (code !in 200..299) {
                throw IllegalStateException("relay config HTTP $code: ${clip(raw)}")
            }
            val hint = JSONObject(raw).optString("pepper_hint", "")
            if (hint.isBlank()) {
                throw IllegalStateException("relay config 缺 pepper_hint")
            }
            return hint
        } finally {
            conn.disconnect()
        }
    }

    private fun deviceIdHash(pepperHint: String): String {
        val androidId = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty()
        if (androidId.isBlank()) {
            throw IllegalStateException("无法读取 ANDROID_ID")
        }
        val material = "$androidId:$pepperHint"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    private fun parseClassify(raw: String): CloudMaskDetector.Result {
        val json = JSONObject(raw)
        val voteRaw = json.optString("vote", "")
        if (voteRaw.isBlank()) {
            throw IllegalStateException("relay 响应无 vote")
        }
        val modelText = json.optString("raw_model_text", "").ifBlank { voteRaw }
        val parsed = MiniMaxCloudMaskDetector.parseLabel(
            if (modelText.contains(voteRaw, ignoreCase = true)) modelText else voteRaw,
        )
        // Prefer server's explicit vote when LabelParser would diverge.
        val vote = voteFromServer(voteRaw) ?: parsed.vote
        val usageObj = json.optJSONObject("usage")
        val usageRaw = usageObj?.toString() ?: raw
        val usage = CloudUsage.parse(usageRaw)
        if (usage.known) {
            Log.i(LOG_TAG, "relay usage prompt=${usage.promptTokens} completion=${usage.completionTokens}")
        }
        val reason = when (vote) {
            FrameVote.CLOSER_TO_MASK_ON -> "中转：戴着面罩"
            FrameVote.CLOSER_TO_MASK_OFF -> "中转：面罩脱落/未戴"
            FrameVote.NO_PERSON -> "中转：无人或过暗"
            FrameVote.UNCERTAIN -> "中转：看不清（${modelText.take(40)}）"
        }
        return CloudMaskDetector.Result(
            vote = vote,
            confidence = if (vote == FrameVote.UNCERTAIN) 0.35f else 0.85f,
            reason = reason,
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
        )
    }

    private fun voteFromServer(voteRaw: String): FrameVote? = when (voteRaw.trim().uppercase()) {
        "MASK_ON" -> FrameVote.CLOSER_TO_MASK_ON
        "MASK_OFF" -> FrameVote.CLOSER_TO_MASK_OFF
        "NO_PERSON" -> FrameVote.NO_PERSON
        "UNCERTAIN" -> FrameVote.UNCERTAIN
        else -> null
    }

    private fun open(method: String, path: String, readTimeoutMs: Int): HttpURLConnection {
        val url = URL("$root$path")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            this.readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Cpap-Client", "android")
            setRequestProperty("X-Cpap-App-Version", appVersion)
            setRequestProperty("X-Cpap-Contract", "1")
        }
    }

    private fun writeBody(conn: HttpURLConnection, body: String) {
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { w ->
            w.write(body)
            w.flush()
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.use { inp ->
            BufferedReader(InputStreamReader(inp, StandardCharsets.UTF_8)).readText()
        }.orEmpty()
    }

    private fun clearToken() {
        synchronized(lock) {
            accessToken = null
            tokenExpiresAtMs = 0L
        }
    }

    private fun clip(text: String): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length > 160) oneLine.take(160) else oneLine
    }

    private class UnauthorizedException(message: String) : IllegalStateException(message)

    companion object {
        const val DEFAULT_BASE = "https://cpap.njxxzf.cn"
        const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_AUTH_MS = 12_000
        private const val READ_CLASSIFY_MS = 20_000
        private val OFF_VALUES = setOf("off", "none", "false")

        fun normalizeBase(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return DEFAULT_BASE
            val lower = trimmed.lowercase()
            if (lower in OFF_VALUES) return null
            return trimmed.trimEnd('/')
        }
    }
}
