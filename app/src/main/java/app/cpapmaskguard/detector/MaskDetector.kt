package app.cpapmaskguard.detector

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import app.cpapmaskguard.BuildConfig
import app.cpapmaskguard.LOG_TAG
import app.cpapmaskguard.core.BaselineComparator
import app.cpapmaskguard.core.Comparison
import app.cpapmaskguard.core.DetectionConfig
import app.cpapmaskguard.core.EmbeddingClusterClassifier
import app.cpapmaskguard.core.FaceFeatures
import app.cpapmaskguard.core.FrameVote
import app.cpapmaskguard.twoDigits
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking

/**
 * 监测判断入口。
 * 主路径：MiniMax-M3 云端看图；失败/超时/无 Key → 本机 TFLite embedding；再不行 → 灰度比对。
 */
class MaskDetector(
    context: Context,
    private val store: BaselineStore,
) : Closeable {
    private val appContext = context.applicationContext
    private val debouncer = app.cpapmaskguard.core.Debouncer()
    private val embedder = TfliteEmbedder(appContext)
    private val cloud: CloudMaskDetector = run {
        val key = BuildConfig.MINIMAX_API_KEY.trim()
        if (key.isNotEmpty()) {
            MiniMaxCloudMaskDetector(apiKey = key)
        } else {
            UnimplementedCloudMaskDetector()
        }
    }

    private var embeddingClassifier: EmbeddingClusterClassifier? = null
    private var grayComparator: BaselineComparator? = null
    private var mode = Mode.MISSING
    private var usingTflite = false

    enum class Mode { READY, SKIPPED, MISSING }

    data class FrameDecision(
        val shouldAlarm: Boolean,
        val summary: String,
        val confidence: Float,
        val streak: Int,
        val vote: FrameVote,
        val usedFaceDetector: Boolean,
        val engine: String,
        val inferMs: Long,
    )

    fun prepare(calibrationSkipped: Boolean): String {
        debouncer.reset()
        embeddingClassifier = null
        grayComparator = null
        usingTflite = false
        store.migrateLegacyIfNeeded()
        if (calibrationSkipped) {
            mode = Mode.SKIPPED
            return "已跳过校准（仅调试）。会拍照，但不会判断，也不会响铃。"
        }

        val cloudHint = if (cloud.configured) {
            "云端 M3 为主"
        } else {
            "未配云端 Key，仅本机"
        }

        // 云端不依赖本机基准；仍尽量准备本机备用
        val localReady = prepareLocalFallback()
        if (cloud.configured) {
            mode = Mode.READY
            return if (localReady != null) {
                "$cloudHint；本机备用已就绪。$localReady"
            } else {
                "$cloudHint；本机备用未就绪（断网时可能无法判定）。建议仍拍多角度基准。"
            }
        }

        if (localReady != null) {
            mode = Mode.READY
            return "$cloudHint。$localReady"
        }

        if (!store.isComplete()) {
            mode = Mode.MISSING
            return "基准还不够：每类至少 ${DetectionConfig.BASELINE_MIN_PER_KIND} 张（不戴 / 戴着）。且未配云端 Key。"
        }
        mode = Mode.MISSING
        return "本机模型不可用，且灰度基准也读不出。且未配云端 Key。"
    }

    /** @return 就绪时的提示短句；失败返回 null */
    private fun prepareLocalFallback(): String? {
        if (!store.isComplete()) return null

        if (embedder.available) {
            var onEmb = store.loadEmbeddings(BaselineStore.Kind.WITH_MASK)
            var offEmb = store.loadEmbeddings(BaselineStore.Kind.WITHOUT_MASK)
            if (onEmb.size < DetectionConfig.BASELINE_MIN_PER_KIND) {
                onEmb = recomputeEmbeddings(BaselineStore.Kind.WITH_MASK)
            }
            if (offEmb.size < DetectionConfig.BASELINE_MIN_PER_KIND) {
                offEmb = recomputeEmbeddings(BaselineStore.Kind.WITHOUT_MASK)
            }
            if (onEmb.isNotEmpty() && offEmb.isNotEmpty()) {
                val clf = EmbeddingClusterClassifier(maskOn = onEmb, maskOff = offEmb)
                embeddingClassifier = clf
                usingTflite = true
                val sep = clf.clusterSeparation()
                Log.i(LOG_TAG, "TFLite 备用簇就绪 on=${onEmb.size} off=${offEmb.size} sep=${sep.twoDigits()}")
                return if (sep < DetectionConfig.EMBED_MIN_BASELINE_SEPARATION) {
                    "本机模型可用，但两类基准太像。"
                } else {
                    "本机模型可用（${onEmb.size}+${offEmb.size} 张）。"
                }
            }
        }

        val pair = loadGrayPair() ?: return null
        grayComparator = BaselineComparator(maskOn = pair.on, maskOff = pair.off)
        usingTflite = false
        Log.w(LOG_TAG, "TFLite 不可用，灰度作本机备用")
        return "本机仅灰度比对（精度较差）。"
    }

    /**
     * @param preferCloud 监测默认 true；烤机压测传 false，强制走本机负载
     */
    fun onCapturedFile(
        file: File,
        debugCrop: File?,
        preferCloud: Boolean = true,
    ): FrameDecision {
        val bitmap = BitmapDecode.decode(file, ImageLimits.DECODE_MAX_SIDE)
        return try {
            // 云端 classify 为 suspend；监测循环已在后台线程，此处 runBlocking 可接受
            runBlocking {
                decideBitmap(bitmap, debugCrop, preferCloud, jpegFile = file)
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun embedBitmap(bitmap: Bitmap): FloatArray? {
        if (!embedder.available) return null
        return try {
            embedder.embed(bitmap)
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "embed 失败", error)
            null
        }
    }

    fun resetDebounce() {
        debouncer.reset()
    }

    override fun close() {
        embedder.close()
    }

    private suspend fun decideBitmap(
        bitmap: Bitmap,
        debugCrop: File?,
        preferCloud: Boolean,
        jpegFile: File?,
    ): FrameDecision {
        if (mode != Mode.READY) {
            val summary = if (mode == Mode.SKIPPED) {
                "调试跳过了校准，这张已丢弃，不会判断。"
            } else {
                "没有基准，这张已丢弃。"
            }
            return FrameDecision(false, summary, 0f, 0, FrameVote.UNCERTAIN, false, "none", 0L)
        }

        val luma = LumaStats.of(bitmap)
        if (debugCrop != null) {
            val cropped = FaceCropper.crop(bitmap)
            try {
                debugCrop.parentFile?.mkdirs()
                FileOutputStream(debugCrop).use { out ->
                    cropped.full.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            } finally {
                if (!cropped.full.isRecycled) cropped.full.recycle()
                if (!cropped.lower.isRecycled) cropped.lower.recycle()
            }
        }

        if (preferCloud && cloud.configured) {
            val started = SystemClock.elapsedRealtime()
            try {
                val jpegBytes = jpegForCloud(bitmap, jpegFile)
                val cloudResult = cloud.classify(jpegBytes)
                val inferMs = SystemClock.elapsedRealtime() - started
                val comparison = Comparison(
                    vote = cloudResult.vote,
                    confidence = cloudResult.confidence,
                    distanceToMaskOn = -1f,
                    distanceToMaskOff = -1f,
                    reason = cloudResult.reason,
                )
                return finish(comparison, inferMs, "minimax-m3")
            } catch (error: Throwable) {
                val inferMs = SystemClock.elapsedRealtime() - started
                Log.w(LOG_TAG, "云端判定失败（${inferMs}ms），回退本机: ${error.message}")
                // fall through to local
            }
        }

        return decideLocal(bitmap, luma)
    }

    private fun decideLocal(bitmap: Bitmap, luma: LumaStats.Stats): FrameDecision {
        if (usingTflite && embeddingClassifier != null && embedder.available) {
            val started = SystemClock.elapsedRealtime()
            val embedding = try {
                val crop = FaceCropper.crop(bitmap)
                try {
                    val lowerEmb = embedder.embed(crop.lower)
                    val fullEmb = embedder.embed(crop.full)
                    val clf = embeddingClassifier!!
                    val lowerCmp = clf.compare(lowerEmb, frameTooDark = luma.tooDarkForPerson)
                    val fullCmp = clf.compare(fullEmb, frameTooDark = luma.tooDarkForPerson)
                    fun closerOf(c: Comparison): Float {
                        val a = c.distanceToMaskOn
                        val b = c.distanceToMaskOff
                        if (a < 0f && b < 0f) return Float.MAX_VALUE
                        if (a < 0f) return b
                        if (b < 0f) return a
                        return minOf(a, b)
                    }
                    if (closerOf(fullCmp) + 0.02f < closerOf(lowerCmp)) {
                        fullEmb
                    } else {
                        lowerEmb
                    }
                } finally {
                    if (!crop.full.isRecycled) crop.full.recycle()
                    if (!crop.lower.isRecycled) crop.lower.recycle()
                }
            } catch (error: Throwable) {
                Log.w(LOG_TAG, "TFLite 推理失败", error)
                null
            }
            val inferMs = SystemClock.elapsedRealtime() - started
            if (embedding != null) {
                if (inferMs > DetectionConfig.LOCAL_INFER_SOFT_TIMEOUT_MS) {
                    Log.w(LOG_TAG, "本机推理 ${inferMs}ms 超过软超时")
                }
                val comparison = embeddingClassifier!!.compare(
                    embedding,
                    frameTooDark = luma.tooDarkForPerson,
                )
                return finish(comparison, inferMs, "tflite-fallback")
            }
        }

        val pipeline = FeaturePipeline.fromBitmap(bitmap)
        return try {
            val comparison = if (luma.tooDarkForPerson) {
                Comparison(
                    vote = FrameVote.NO_PERSON,
                    confidence = 0f,
                    distanceToMaskOn = -1f,
                    distanceToMaskOff = -1f,
                    reason = "画面几乎全黑，当作无人，不报警",
                )
            } else {
                grayComparator?.compare(pipeline.features)
                    ?: Comparison(
                        FrameVote.UNCERTAIN, 0f, -1f, -1f, "没有灰度比对器",
                    )
            }
            finish(comparison, 0L, "gray-fallback")
        } finally {
            pipeline.recycleCrops()
        }
    }

    /** 优先用已有 JPEG；过大则按长边缩到云端上限再压 JPEG。 */
    private fun jpegForCloud(bitmap: Bitmap, jpegFile: File?): ByteArray {
        val maxBytes = 900_000
        if (jpegFile != null && jpegFile.exists() && jpegFile.length() in 1 until maxBytes.toLong()) {
            return jpegFile.readBytes()
        }
        val maxSide = 640
        val scaled = if (bitmap.width > maxSide || bitmap.height > maxSide) {
            val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        return try {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            out.toByteArray()
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        }
    }

    private fun finish(
        comparison: Comparison,
        inferMs: Long,
        engine: String,
    ): FrameDecision {
        val crossed = debouncer.onVote(comparison.vote)
        val shouldAlarm = crossed && comparison.vote == FrameVote.CLOSER_TO_MASK_OFF
        val summary = buildString {
            append(comparison.reason)
            append(" · ")
            append(engine)
            if (inferMs > 0) {
                append(" ")
                append(inferMs)
                append("ms")
            }
            append(" · 区分度 ")
            append(comparison.confidence.twoDigits())
            append(" · 连续 ")
            append(debouncer.maskOffStreak)
            append("/")
            append(DetectionConfig.DEBOUNCE_CONSECUTIVE)
            if (comparison.distanceToMaskOn >= 0f) {
                append(" · 戴 ")
                append(comparison.distanceToMaskOn.twoDigits())
                append(" / 未戴 ")
                append(comparison.distanceToMaskOff.twoDigits())
            }
        }
        Log.i(LOG_TAG, summary)
        return FrameDecision(
            shouldAlarm = shouldAlarm,
            summary = summary,
            confidence = comparison.confidence,
            streak = debouncer.maskOffStreak,
            vote = comparison.vote,
            usedFaceDetector = false,
            engine = engine,
            inferMs = inferMs,
        )
    }

    private fun recomputeEmbeddings(kind: BaselineStore.Kind): List<FloatArray> {
        if (!embedder.available) return emptyList()
        val out = mutableListOf<FloatArray>()
        for (jpeg in store.jpegFiles(kind)) {
            val bitmap = BitmapDecode.decode(jpeg, ImageLimits.DECODE_MAX_SIDE)
            try {
                val crop = FaceCropper.crop(bitmap)
                try {
                    val emb = embedder.embed(crop.lower)
                    writeEmb(store.embeddingFileFor(jpeg), emb)
                    out += emb
                } finally {
                    if (!crop.full.isRecycled) crop.full.recycle()
                    if (!crop.lower.isRecycled) crop.lower.recycle()
                }
            } catch (error: Throwable) {
                Log.w(LOG_TAG, "补算 embedding 失败 ${jpeg.name}", error)
            } finally {
                bitmap.recycle()
            }
        }
        return out
    }

    private fun writeEmb(file: File, values: FloatArray) {
        java.io.DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeInt(values.size)
            for (v in values) out.writeFloat(v)
        }
    }

    private data class GrayPair(val on: FaceFeatures, val off: FaceFeatures)

    private fun loadGrayPair(): GrayPair? {
        val onFile = store.jpegFiles(BaselineStore.Kind.WITH_MASK).firstOrNull() ?: return null
        val offFile = store.jpegFiles(BaselineStore.Kind.WITHOUT_MASK).firstOrNull() ?: return null
        val on = featuresOf(onFile) ?: return null
        val off = featuresOf(offFile) ?: return null
        return GrayPair(on, off)
    }

    private fun featuresOf(file: File): FaceFeatures? {
        val bitmap = BitmapDecode.decode(file, ImageLimits.DECODE_MAX_SIDE)
        return try {
            val pipeline = FeaturePipeline.fromBitmap(bitmap)
            val features = pipeline.features
            pipeline.recycleCrops()
            features
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "灰度特征失败", error)
            null
        } finally {
            bitmap.recycle()
        }
    }
}
