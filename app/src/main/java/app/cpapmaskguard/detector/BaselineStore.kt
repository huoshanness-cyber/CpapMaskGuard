package app.cpapmaskguard.detector

import android.content.Context
import app.cpapmaskguard.core.DetectionConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/**
 * 多角度多张基准，存在应用私有目录：
 * `files/baselines/without_mask/00.jpg` … `with_mask/04.jpg`
 * 以及对应的 `00.emb`（float32 embedding，本机模型算好后缓存）。
 * 照片不进相册，代码默认不上云。
 */
class BaselineStore(context: Context) {
    private val dir = File(context.filesDir, "baselines").apply { mkdirs() }
    private val metaFile = File(dir, "meta.properties")

    enum class Kind(val dirName: String) {
        WITHOUT_MASK("without_mask"),
        WITH_MASK("with_mask"),
    }

    fun kindDir(kind: Kind): File = File(dir, kind.dirName).apply { mkdirs() }

    fun jpegFiles(kind: Kind): List<File> =
        kindDir(kind).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jpg", ignoreCase = true) && it.length() > 0L }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun embeddingFileFor(jpeg: File): File =
        File(jpeg.parentFile, jpeg.nameWithoutExtension + ".emb")

    fun count(kind: Kind): Int = jpegFiles(kind).size

    fun isComplete(
        minPerKind: Int = DetectionConfig.BASELINE_MIN_PER_KIND,
    ): Boolean = Kind.entries.all { count(it) >= minPerKind }

    fun progressLabel(kind: Kind, target: Int = DetectionConfig.BASELINE_TARGET_PER_KIND): String {
        val name = if (kind == Kind.WITHOUT_MASK) "不戴" else "戴着"
        return "$name ${count(kind).coerceAtMost(target)}/$target"
    }

    /**
     * 追加一张基准。返回该类当前张数。
     * [embedding] 可空：本机模型不可用时只存 JPEG，监测走灰度 fallback。
     */
    fun add(
        kind: Kind,
        jpeg: File,
        embedding: FloatArray?,
        dark: Boolean,
    ): Int {
        check(jpeg.isFile && jpeg.length() > 0L) { "没有可保存的照片" }
        val destDir = kindDir(kind)
        val index = count(kind)
        val dest = File(destDir, "%02d.jpg".format(index))
        jpeg.copyTo(dest, overwrite = true)
        if (embedding != null) {
            writeEmbedding(embeddingFileFor(dest), embedding)
        }
        synchronized(dir) {
            val props = readProps()
            val prefix = if (kind == Kind.WITHOUT_MASK) "without" else "with"
            props.setProperty("${prefix}_count", count(kind).toString())
            props.setProperty("${prefix}_dark_$index", dark.toString())
            props.setProperty("${prefix}_at_$index", System.currentTimeMillis().toString())
            props.setProperty("${prefix}_last_at", System.currentTimeMillis().toString())
            FileOutputStream(metaFile).use { props.store(it, "cpap mask baselines v2 multi-shot") }
        }
        return count(kind)
    }

    fun loadEmbeddings(kind: Kind): List<FloatArray> {
        return jpegFiles(kind).mapNotNull { jpeg ->
            val emb = embeddingFileFor(jpeg)
            if (emb.isFile && emb.length() > 0L) readEmbedding(emb) else null
        }
    }

    fun hasAnyEmbeddings(): Boolean =
        Kind.entries.any { loadEmbeddings(it).isNotEmpty() }

    fun meta(): BaselineMeta? {
        if (!metaFile.isFile && Kind.entries.all { count(it) == 0 }) return null
        val without = count(Kind.WITHOUT_MASK)
        val with = count(Kind.WITH_MASK)
        val props = readProps()
        return BaselineMeta(
            withoutCount = without,
            withCount = with,
            withoutAt = props.getProperty("without_last_at")?.toLongOrNull() ?: 0L,
            withAt = props.getProperty("with_last_at")?.toLongOrNull() ?: 0L,
            anyDark = Kind.entries.any { kind ->
                jpegFiles(kind).indices.any { i ->
                    val prefix = if (kind == Kind.WITHOUT_MASK) "without" else "with"
                    props.getProperty("${prefix}_dark_$i")?.toBooleanStrictOrNull() == true
                }
            },
        )
    }

    fun clear() {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) child.listFiles()?.forEach { it.delete() }
            child.delete()
        }
        dir.mkdirs()
    }

    /** 兼容旧版单文件 `without_mask.jpg` / `with_mask.jpg`：若存在则迁到 00.jpg。 */
    fun migrateLegacyIfNeeded() {
        for (kind in Kind.entries) {
            val legacy = File(dir, when (kind) {
                Kind.WITHOUT_MASK -> "without_mask.jpg"
                Kind.WITH_MASK -> "with_mask.jpg"
            })
            if (legacy.isFile && legacy.length() > 0L && count(kind) == 0) {
                val dest = File(kindDir(kind), "00.jpg")
                legacy.copyTo(dest, overwrite = true)
                legacy.delete()
            }
        }
    }

    private fun writeEmbedding(file: File, values: FloatArray) {
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeInt(values.size)
            for (v in values) out.writeFloat(v)
        }
    }

    private fun readEmbedding(file: File): FloatArray {
        DataInputStream(FileInputStream(file)).use { input ->
            val n = input.readInt()
            return FloatArray(n) { input.readFloat() }
        }
    }

    private fun readProps(): Properties {
        val props = Properties()
        if (metaFile.isFile) {
            FileInputStream(metaFile).use { props.load(it) }
        }
        return props
    }
}

data class BaselineMeta(
    val withoutCount: Int,
    val withCount: Int,
    val withoutAt: Long,
    val withAt: Long,
    val anyDark: Boolean,
) {
    fun hint(): String {
        val notes = mutableListOf<String>()
        notes += "不戴 ${withoutCount} 张 / 戴着 ${withCount} 张"
        if (anyDark) notes += "有的偏暗"
        return notes.joinToString("，")
    }
}
