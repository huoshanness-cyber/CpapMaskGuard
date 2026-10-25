package app.cpapmaskguard.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddingClusterClassifierTest {
    @Test
    fun nearerToOffClusterVotesMaskOff() {
        val on = listOf(vec(1f, 0f), vec(0.95f, 0.05f))
        val off = listOf(vec(0f, 1f), vec(0.05f, 0.95f))
        val clf = EmbeddingClusterClassifier(on, off)
        val result = clf.compare(vec(0.1f, 0.9f))
        assertEquals(FrameVote.CLOSER_TO_MASK_OFF, result.vote)
        assertTrue(result.distanceToMaskOff < result.distanceToMaskOn)
    }

    @Test
    fun nearerToOnClusterVotesMaskOn() {
        val on = listOf(vec(1f, 0f))
        val off = listOf(vec(0f, 1f))
        val clf = EmbeddingClusterClassifier(on, off)
        val result = clf.compare(vec(0.9f, 0.1f))
        assertEquals(FrameVote.CLOSER_TO_MASK_ON, result.vote)
    }

    @Test
    fun farFromAllPrefersMaskOnNotNoPerson() {
        val on = listOf(vec(1f, 0f))
        val off = listOf(vec(0f, 1f))
        val clf = EmbeddingClusterClassifier(on, off)
        // 两端都远（>0.55），但对戴罩更近 → 面罩正常，绝不「未检测到人」
        val result = clf.compare(vec(-0.2f, -1f))
        assertEquals(FrameVote.CLOSER_TO_MASK_ON, result.vote)
        assertTrue(result.distanceToMaskOn < result.distanceToMaskOff)
    }

    @Test
    fun farCloserToOffStaysUncertainNotNoPerson() {
        val on = listOf(vec(1f, 0f))
        val off = listOf(vec(0f, 1f))
        val clf = EmbeddingClusterClassifier(on, off)
        val result = clf.compare(vec(-1f, -0.2f))
        assertEquals(FrameVote.UNCERTAIN, result.vote)
        assertTrue(result.distanceToMaskOff < result.distanceToMaskOn)
    }

    @Test
    fun tooDarkIsNoPerson() {
        val clf = EmbeddingClusterClassifier(listOf(vec(1f, 0f)), listOf(vec(0f, 1f)))
        val result = clf.compare(vec(1f, 0f), frameTooDark = true)
        assertEquals(FrameVote.NO_PERSON, result.vote)
    }

    @Test
    fun ambiguousStaysUncertain() {
        val on = listOf(vec(1f, 0f))
        val off = listOf(vec(0f, 1f))
        val clf = EmbeddingClusterClassifier(on, off)
        val mid = clf.compare(vec(0.7071f, 0.7071f))
        assertEquals(FrameVote.UNCERTAIN, mid.vote)
    }

    private fun vec(vararg values: Float): FloatArray = EmbeddingMath.l2Normalize(values)
}
