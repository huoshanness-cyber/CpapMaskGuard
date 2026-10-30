package app.cpapmaskguard.detector

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * 云端视觉检测：主路径为 MiniMax-M3 看图分类。
 * 网络失败 / 超时 / 无 Key 时由 [MaskDetector] 回退本机 embedding。
 */
interface CloudMaskDetector {
    data class Result(
        val vote: FrameVote,
        val confidence: Float,
        val reason: String,
        val promptTokens: Int = -1,
        val completionTokens: Int = -1,
    )

    /** 是否已配置可用 Key，可发起请求。 */
    val configured: Boolean

    /**
     * @param jpegBytes 一帧 JPEG（建议已缩到较小分辨率）
     * @return 分类结果；失败时抛异常，由调用方回退本机
     */
    suspend fun classify(jpegBytes: ByteArray, onUploaded: (() -> Unit)? = null): Result
}

/** 占位：无 Key 时使用，classify 直接失败。 */
class UnimplementedCloudMaskDetector : CloudMaskDetector {
    override val configured: Boolean = false

    override suspend fun classify(jpegBytes: ByteArray, onUploaded: (() -> Unit)?): CloudMaskDetector.Result {
        throw IllegalStateException("未配置 MiniMax API Key，跳过云端。")
    }
}

/**
 * MiniMax Chat Completions（OpenAI 兼容）看图判定。
 * POST https://api.minimax.cn/v1/chat/completions · model MiniMax-M3
 */
class MiniMaxCloudMaskDetector(
    private val apiKey: String,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val model: String = DEFAULT_MODEL,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : CloudMaskDetector {
    override val configured: Boolean = apiKey.isNotBlank()

    override suspend fun classify(jpegBytes: ByteArray, onUploaded: (() -> Unit)?): CloudMaskDetector.Result {
        if (!configured) {
            throw IllegalStateException("MiniMax API Key 为空")
        }
        if (jpegBytes.isEmpty()) {
            throw IllegalArgumentException("空 JPEG")
        }
        return withContext(Dispatchers.IO) {
            withTimeout(timeoutMs) {
                postClassify(jpegBytes, onUploaded)
            }
        }
    }

    private fun postClassify(jpegBytes: ByteArray, onUploaded: (() -> Unit)?): CloudMaskDetector.Result {
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val dataUrl = "data:image/jpeg;base64,$b64"
        val body = JSONObject()
            .put("model", model)
            .put(
                "thinking",
                JSONObject().put("type", "disabled"),
            )
            .put("max_completion_tokens", 64)
            .put("temperature", 0.1)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("type", "text")
                                        .put("text", PROMPT),
                                )
                                .put(
                                    JSONObject()
                                        .put("type", "image_url")
                                        .put(
                                            "image_url",
                                            JSONObject()
                                                .put("url", dataUrl)
                                                .put("detail", "low"),
                                        ),
                                ),
                        ),
                ),
            )
            .toString()

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }
        try {
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { w ->
                w.write(body)
                w.flush()
            }
            onUploaded?.invoke()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.use { inp ->
                BufferedReader(InputStreamReader(inp, StandardCharsets.UTF_8)).readText()
            }.orEmpty()
            if (code !in 200..299) {
                val snippet = raw.take(240).replace(Regex("\\s+"), " ")
                throw IllegalStateException("MiniMax HTTP $code: $snippet")
            }
            return parseResponse(raw)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(raw: String): CloudMaskDetector.Result {
        val root = JSONObject(raw)
        val base = root.optJSONObject("base_resp")
        if (base != null) {
            val status = base.optInt("status_code", 0)
            if (status != 0) {
                val msg = base.optString("status_msg", "error")
                throw IllegalStateException("MiniMax base_resp=$status $msg")
            }
        }
        val choices = root.optJSONArray("choices")
            ?: throw IllegalStateException("响应无 choices")
        if (choices.length() == 0) {
            throw IllegalStateException("choices 为空")
        }
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw IllegalStateException("无 message")
        val content = message.optString("content", "").trim()
        if (content.isEmpty()) {
            throw IllegalStateException("模型返回空 content")
        }
        val usage = CloudUsage.parse(raw)
        if (usage.known) {
            Log.i(LOG_TAG, "MiniMax usage prompt=${usage.promptTokens} completion=${usage.completionTokens}")
        }
        return parseLabel(content).copy(
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
        )
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.minimax.cn/v1/chat/completions"
        const val DEFAULT_MODEL = "MiniMax-M3"
        const val DEFAULT_TIMEOUT_MS = 12_000L
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000

        /** Same text as iOS `MiniMaxClient.prompt`. Side sleep stays MASK_ON. */
        private val PROMPT = """
            You classify one night-camera still for a CPAP/BiPAP mask alarm.
            Reply with EXACTLY one label and nothing else (no punctuation, no explanation):
            MASK_ON — a person is present and wearing a CPAP/BiPAP nasal or full-face mask
            MASK_OFF — a person is present but the mask is clearly off the face (removed, hanging away, or missing)
            NO_PERSON — the person is obviously absent, or the frame is completely unreadable
            UNCERTAIN — cannot tell (blurry, extreme angle, ambiguous)
            Side sleep is still MASK_ON when the mask stays on: the person is lying on their side, only the back of the head or a side of the face is visible, or the headband / headgear or the corrugated tube is still on the face or beside the pillow. Do not label MASK_OFF only because of pose while the mask is still worn.
            侧睡、只见后脑勺或侧脸、头带或波纹管仍在脸上或床边，仍判 MASK_ON。不要因为姿态把仍戴着的面罩判成 MASK_OFF。
            NO_PERSON only when the person is clearly not in the frame or the frame cannot be seen at all. 人明显不在画面里，或完全看不清，才用 NO_PERSON。
        """.trimIndent()

        fun parseLabel(content: String): CloudMaskDetector.Result {
            val upper = content.uppercase()
            // Prefer longest / most specific match first
            // Check OFF before ON so a lone "MASK_OFF" never matches ON by mistake.
            val vote = when {
                upper.contains("MASK_OFF") -> FrameVote.CLOSER_TO_MASK_OFF
                upper.contains("MASK_ON") -> FrameVote.CLOSER_TO_MASK_ON
                upper.contains("NO_PERSON") || upper.contains("NO PERSON") -> FrameVote.NO_PERSON
                upper.contains("UNCERTAIN") -> FrameVote.UNCERTAIN
                else -> {
                    Log.w(LOG_TAG, "云端标签无法解析: ${content.take(80)}")
                    FrameVote.UNCERTAIN
                }
            }
            val confidence = when (vote) {
                FrameVote.UNCERTAIN -> 0.35f
                else -> 0.85f
            }
            val reason = when (vote) {
                FrameVote.CLOSER_TO_MASK_ON -> "云端：戴着面罩"
                FrameVote.CLOSER_TO_MASK_OFF -> "云端：面罩脱落/未戴"
                FrameVote.NO_PERSON -> "云端：无人或过暗"
                FrameVote.UNCERTAIN -> "云端：看不清（${content.take(40)}）"
            }
            return CloudMaskDetector.Result(vote = vote, confidence = confidence, reason = reason)
        }
    }
}
