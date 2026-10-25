package app.cpapmaskguard.detector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import app.cpapmaskguard.LOG_TAG
import app.cpapmaskguard.core.EmbeddingMath
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/**
 * 本机 MobileNet V3 Small 特征提取（MediaPipe image embedder 权重）。
 * 资产：[ASSET_NAME]。输入 224×224 RGB float32 [0,1]，输出 1024-d，再 L2 归一化。
 *
 * 若资产缺失或 Interpreter 初始化失败，[available] 为 false，调用方走灰度 fallback。
 */
class TfliteEmbedder(context: Context) : Closeable {
    private var interpreter: Interpreter? = null
    private var inputHeight = 224
    private var inputWidth = 224
    private var outputSize = 1024
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: Array<FloatArray>? = null

    val available: Boolean get() = interpreter != null

    init {
        try {
            val model = loadModelFile(context, ASSET_NAME)
            val options = Interpreter.Options().apply { setNumThreads(2) }
            val interp = Interpreter(model, options)
            val inShape = interp.getInputTensor(0).shape() // [1,H,W,3]
            val outShape = interp.getOutputTensor(0).shape() // [1,D]
            inputHeight = inShape[1]
            inputWidth = inShape[2]
            outputSize = outShape[outShape.size - 1]
            inputBuffer = ByteBuffer.allocateDirect(4 * inputHeight * inputWidth * 3)
                .order(ByteOrder.nativeOrder())
            outputBuffer = Array(1) { FloatArray(outputSize) }
            interpreter = interp
            Log.i(LOG_TAG, "TFLite embedder ready ${inputWidth}x$inputHeight → $outputSize-d")
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "TFLite embedder 初始化失败，将用灰度 fallback", error)
            close()
        }
    }

    fun embed(bitmap: Bitmap): FloatArray {
        val interp = interpreter ?: error("TFLite 模型不可用")
        val buf = inputBuffer ?: error("input buffer missing")
        val out = outputBuffer ?: error("output buffer missing")
        val scaled = if (bitmap.width == inputWidth && bitmap.height == inputHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        }
        try {
            buf.rewind()
            val pixels = IntArray(inputWidth * inputHeight)
            scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            for (c in pixels) {
                buf.putFloat(((c shr 16) and 0xFF) / 255f)
                buf.putFloat(((c shr 8) and 0xFF) / 255f)
                buf.putFloat((c and 0xFF) / 255f)
            }
            buf.rewind()
            interp.run(buf, out)
            return EmbeddingMath.l2Normalize(out[0].copyOf())
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        }
    }

    override fun close() {
        try {
            interpreter?.close()
        } catch (_: Throwable) {
        }
        interpreter = null
        inputBuffer = null
        outputBuffer = null
    }

    companion object {
        const val ASSET_NAME = "mobilenet_v3_small_embedder.tflite"

        private fun loadModelFile(context: Context, assetName: String): ByteBuffer {
            context.assets.open(assetName).use { input ->
                val bytes = input.readBytes()
                return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                    put(bytes)
                    rewind()
                }
            }
        }
    }
}
