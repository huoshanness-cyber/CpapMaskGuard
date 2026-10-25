package app.cpapmaskguard.core

import kotlin.math.sqrt

/** 纯 Kotlin 的 embedding 工具：L2 归一化与余弦距离（1 - similarity）。 */
object EmbeddingMath {
    fun l2Normalize(values: FloatArray): FloatArray {
        var sumSq = 0.0
        for (v in values) sumSq += v.toDouble() * v.toDouble()
        val norm = sqrt(sumSq).coerceAtLeast(1e-9)
        return FloatArray(values.size) { (values[it] / norm).toFloat() }
    }

    /** 余弦距离：0 表示同向，2 表示反向。输入应已 L2 归一化。 */
    fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size && a.isNotEmpty()) { "embedding 长度不一致" }
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i].toDouble()
        val sim = dot.coerceIn(-1.0, 1.0)
        return (1.0 - sim).toFloat()
    }

    fun nearestDistance(query: FloatArray, cluster: List<FloatArray>): Float {
        require(cluster.isNotEmpty()) { "空簇" }
        var best = Float.POSITIVE_INFINITY
        for (item in cluster) {
            val d = cosineDistance(query, item)
            if (d < best) best = d
        }
        return best
    }

    fun meanVector(cluster: List<FloatArray>): FloatArray {
        require(cluster.isNotEmpty())
        val dim = cluster[0].size
        val acc = DoubleArray(dim)
        for (vec in cluster) {
            require(vec.size == dim)
            for (i in 0 until dim) acc[i] += vec[i].toDouble()
        }
        val n = cluster.size.toDouble()
        return l2Normalize(FloatArray(dim) { (acc[it] / n).toFloat() })
    }
}
