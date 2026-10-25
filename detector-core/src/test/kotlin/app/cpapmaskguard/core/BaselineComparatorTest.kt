package app.cpapmaskguard.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BaselineComparatorTest {
    @Test
    fun identicalFrameMatchesThatBaseline() {
        val on = features(0f)
        val off = features(1f)
        val comparator = BaselineComparator(maskOn = on, maskOff = off)

        val asOff = comparator.compare(features(1f))
        assertEquals(FrameVote.CLOSER_TO_MASK_OFF, asOff.vote)
        assertTrue(asOff.confidence > DetectionConfig.MIN_CONFIDENCE)

        val asOn = comparator.compare(features(0f))
        assertEquals(FrameVote.CLOSER_TO_MASK_ON, asOn.vote)
    }

    @Test
    fun halfwayBetweenBaselinesStaysUncertain() {
        val comparator = BaselineComparator(maskOn = features(0f), maskOff = features(1f))
        val mid = comparator.compare(features(0.5f))
        assertEquals(FrameVote.UNCERTAIN, mid.vote)
    }

    @Test
    fun tinyMarginDoesNotCountAsMaskOff() {
        val comparator = BaselineComparator(maskOn = features(0f), maskOff = features(1f))
        val almostMid = comparator.compare(features(0.52f))
        assertEquals(FrameVote.UNCERTAIN, almostMid.vote)
    }

    @Test
    fun sceneFarFromBothBaselinesDoesNotVote() {
        val comparator = BaselineComparator(maskOn = features(0f), maskOff = features(1f))
        val far = comparator.compare(features(5f))
        assertEquals(FrameVote.UNCERTAIN, far.vote)
        assertTrue(far.reason.contains("差得比较远"))
    }

    @Test
    fun flatDarkFrameDoesNotVote() {
        val comparator = BaselineComparator(maskOn = features(0f), maskOff = features(1f))
        val dark = comparator.compare(features(0f, variance = 0f))
        assertEquals(FrameVote.UNCERTAIN, dark.vote)
        assertTrue(dark.reason.contains("太暗"))
    }

    @Test
    fun lowerFaceOutweighsFullFace() {
        val zeros = region(0f)
        val ones = region(1f)
        val comparator = BaselineComparator(
            maskOn = FaceFeatures(zeros, zeros),
            maskOff = FaceFeatures(ones, ones),
        )
        val lowerLikeOff = comparator.compare(FaceFeatures(full = zeros, lower = ones))
        assertEquals(FrameVote.CLOSER_TO_MASK_OFF, lowerLikeOff.vote)
        assertTrue(lowerLikeOff.distanceToMaskOff < lowerLikeOff.distanceToMaskOn)
    }

    @Test
    fun weightsSumToOne() {
        assertEquals(1f, DetectionConfig.WEIGHT_FULL + DetectionConfig.WEIGHT_LOWER, 0.0001f)
    }

    private fun region(value: Float, variance: Float = 0.05f) =
        EncodedRegion(FloatArray(8) { value }, variance)

    private fun features(value: Float, variance: Float = 0.05f) =
        FaceFeatures(region(value, variance), region(value, variance))
}
