package app.cpapmaskguard.detector

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import app.cpapmaskguard.BuildConfig
import app.cpapmaskguard.LOG_TAG
import app.cpapmaskguard.core.AimGate
import app.cpapmaskguard.core.BaselineComparator
import app.cpapmaskguard.core.CloudCircuit
import app.cpapmaskguard.core.Comparison
import app.cpapmaskguard.core.DetectionConfig
import app.cpapmaskguard.core.EmbeddingClusterClassifier
import app.cpapmaskguard.core.EmbeddingMath
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
            val endpoint = BuildConfig.MINIMAX_ENDPOINT.trim().ifEmpty {
                MiniMaxCloudMaskDetector.DEFAULT_ENDPOINT
            }
            MiniMaxCloudMaskDetector(apiKey = key, endpoint = endpoint)
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
        /** 本机判定镜头没对准。没上云，也不算云端 MASK_OFF。 */
        val aimInvalid: Boolean = false,
        /** 上传已关，这一帧没有请求云端。 */
        val cloudSkipped: Boolean = false,
        /** 仅当这一帧真正拿到云端标签时非空。本机回退和暂停都不填。 */
        val cloudVote: FrameVote? = null,
        val promptTokens: Int = -1,
        val completionTokens: Int = -1,
        val circuit: CloudCircuit.State = CloudCircuit.State(),
        val aimSignature: List<Float> = emptyList(),
        /** 恢复次数用尽后走本机分类，不再只抱着上次云端脱落。 */
        val localOnly: Boolean = false,
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
        uploadAllowed: Boolean = true,
        holdMaskOffAlarm: Boolean = false,
        localFallback: Boolean = false,
        circuit: CloudCircuit.State = CloudCircuit.State(),
    ): FrameDecision {
        val bitmap = BitmapDecode.decode(file, ImageLimits.DECODE_MAX_SIDE)
        return try {
            // 云端 classify 为 suspend；监测循环已在后台线程，此处 runBlocking 可接受
            runBlocking {
                decideBitmap(
                    bitmap,
                    debugCrop,
                    preferCloud,
                    jpegFile = file,
                    uploadAllowed = uploadAllowed,
                    holdMaskOffAlarm = holdMaskOffAlarm,
                    localFallback = localFallback,
                    circuit = circuit,
                )
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
        uploadAllowed: Boolean,
        holdMaskOffAlarm: Boolean,
        localFallback: Boolean,
        circuit: CloudCircuit.State,
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

        if (preferCloud) {
            val started = SystemClock.elapsedRealtime()
            val aim = assessAim(bitmap)
            val aimMs = SystemClock.elapsedRealtime() - started
            if (!aim.verdict.valid) {
                Log.i(LOG_TAG, "对准失败 ${aim.verdict.reason}，跳过云端 ${aimMs}ms")
                return FrameDecision(
                    shouldAlarm = false,
                    summary = AimGate.SOFT_HINT,
                    confidence = 0f,
                    streak = debouncer.maskOffStreak,
                    vote = FrameVote.UNCERTAIN,
                    usedFaceDetector = aim.verdict.reason == AimGate.Reason.FACE,
                    engine = "aim-gate",
                    inferMs = aimMs,
                    aimInvalid = true,
                    circuit = circuit,
                    aimSignature = aim.signature,
                )
            }
            if (cloud.configured && !uploadAllowed && !localFallback) {
                Log.i(LOG_TAG, "上传已关，跳过云端 holdAlarm=$holdMaskOffAlarm")
                return FrameDecision(
                    shouldAlarm = holdMaskOffAlarm,
                    summary = if (holdMaskOffAlarm) {
                        "已暂停上传，仍按上次脱落提醒"
                    } else {
                        "已暂停上传"
                    },
                    confidence = 0f,
                    streak = debouncer.maskOffStreak,
                    vote = if (holdMaskOffAlarm) FrameVote.CLOSER_TO_MASK_OFF else FrameVote.UNCERTAIN,
                    usedFaceDetector = aim.verdict.reason == AimGate.Reason.FACE,
                    engine = "cloud-paused",
                    inferMs = aimMs,
                    cloudSkipped = true,
                    circuit = circuit,
                    aimSignature = aim.signature,
                )
            }
            if (cloud.configured && uploadAllowed) {
                val now = System.currentTimeMillis()
                val action = CloudCircuit.action(circuit, now)
                if (action == CloudCircuit.Action.LOCAL) {
                    Log.i(LOG_TAG, "云端熔断中，本机优先，跳过这一帧的云端等待")
                    return decideLocal(bitmap, luma).copy(
                        circuit = circuit,
                        aimSignature = aim.signature,
                    )
                }
                val attempted = if (action == CloudCircuit.Action.PROBE) {
                    Log.i(LOG_TAG, "云端熔断中，低频探活")
                    CloudCircuit.noteProbe(circuit, now)
                } else {
                    circuit
                }
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
                    return finish(
                        comparison,
                        inferMs,
                        "minimax-m3",
                        cloudVote = cloudResult.vote,
                        promptTokens = cloudResult.promptTokens,
                        completionTokens = cloudResult.completionTokens,
                        circuit = CloudCircuit.onSuccess(attempted),
                        aimSignature = aim.signature,
                    )
                } catch (error: Throwable) {
                    val inferMs = SystemClock.elapsedRealtime() - started
                    val failed = CloudCircuit.onFailure(attempted, System.currentTimeMillis())
                    Log.w(LOG_TAG, "云端判定失败（${inferMs}ms），回退本机: ${error.message}")
                    return decideLocal(bitmap, luma).copy(
                        circuit = failed,
                        aimSignature = aim.signature,
                    )
                }
            }
            if (cloud.configured && localFallback) {
                Log.i(LOG_TAG, "恢复次数已用尽，本机兜底，不再上传")
                return decideLocal(bitmap, luma).copy(
                    circuit = circuit,
                    aimSignature = aim.signature,
                    cloudSkipped = true,
                    localOnly = true,
                )
            }
        }

        return decideLocal(bitmap, luma).copy(circuit = circuit)
    }

    private data class AimAssessment(
        val verdict: AimGate.Verdict,
        val signature: List<Float>,
    )

    /**
     * 人脸不是必要条件。明确全黑/全白/严重模糊才拒绝；拿不准放行。
     * 纹理先算。只有还没放行时才补脸和床头比对，避免每一帧都跑模型。
     */
    private fun assessAim(bitmap: Bitmap): AimAssessment {
        val center = LocalAimProbe.center(bitmap)
        var sample = AimGate.Sample(
            centerMean = center.mean,
            centerVariance = center.variance,
            centerEdge = center.edge,
        )
        var verdict = AimGate.evaluate(sample)
        if (verdict.valid && verdict.reason != AimGate.Reason.UNCERTAIN) {
            return AimAssessment(verdict, center.signature)
        }
        val face = LocalAimProbe.faceWidthFraction(bitmap)
        sample = sample.copy(faceWidthFraction = face)
        verdict = AimGate.evaluate(sample)
        if (verdict.valid && verdict.reason != AimGate.Reason.UNCERTAIN) {
            return AimAssessment(verdict, center.signature)
        }
        if (sceneMatchesBedside(bitmap)) {
            sample = sample.copy(sceneMatchesBedside = true)
            verdict = AimGate.evaluate(sample)
        }
        Log.i(
            LOG_TAG,
            "对准 ${verdict.reason} face=${"%.2f".format(face)} mean=${"%.3f".format(center.mean)} " +
                "var=${"%.4f".format(center.variance)} edge=${"%.3f".format(center.edge)}",
        )
        return AimAssessment(verdict, center.signature)
    }

    private fun sceneMatchesBedside(bitmap: Bitmap): Boolean {
        val classifier = embeddingClassifier ?: return false
        if (!usingTflite || !embedder.available) return false
        return try {
            val crop = FaceCropper.crop(bitmap)
            try {
                val embedding = embedder.embed(crop.lower)
                val query = EmbeddingMath.l2Normalize(embedding)
                val compared = classifier.compare(query, frameTooDark = false)
                val distances = listOf(compared.distanceToMaskOn, compared.distanceToMaskOff).filter { it >= 0f }
                val nearest = distances.minOrNull() ?: return false
                nearest <= DetectionConfig.AIM_SCENE_MAX_DISTANCE
            } finally {
                if (!crop.full.isRecycled) crop.full.recycle()
                if (!crop.lower.isRecycled) crop.lower.recycle()
            }
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "床头场景比对失败", error)
            false
        }
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
        cloudVote: FrameVote? = null,
        promptTokens: Int = -1,
        completionTokens: Int = -1,
        circuit: CloudCircuit.State = CloudCircuit.State(),
        aimSignature: List<Float> = emptyList(),
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
            cloudVote = cloudVote,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            circuit = circuit,
            aimSignature = aimSignature,
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
