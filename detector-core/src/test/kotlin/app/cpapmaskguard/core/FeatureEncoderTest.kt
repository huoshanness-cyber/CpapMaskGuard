package app.cpapmaskguard.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureEncoderTest {
    @Test
    fun encodeUsesFixedSizeAndCentersAroundZero() {
        val gray = FloatArray(48 * 40) { index -> (index % 48) / 48f }
        val encoded = FeatureEncoder.encode(gray, 48, 40)
        assertEquals(FeatureEncoder.SIZE * FeatureEncoder.SIZE, encoded.values.size)
        val mean = encoded.values.average()
        assertTrue(abs(mean) < 1e-3)
        assertTrue(encoded.variance > DetectionConfig.MIN_LUMA_VARIANCE)
    }

    @Test
    fun flatImageHasNoVariance() {
        val gray = FloatArray(32 * 32) { 0.02f }
        val encoded = FeatureEncoder.encode(gray, 32, 32)
        assertTrue(encoded.variance < DetectionConfig.MIN_LUMA_VARIANCE)
    }

    @Test
    fun samePatternIsCloserThanADifferentPattern() {
        val smooth = FeatureEncoder.encode(FloatArray(32 * 32) { 0.4f }, 32, 32)
        val stripes = FeatureEncoder.encode(
            FloatArray(32 * 32) { index -> if (index % 2 == 0) 0.1f else 0.9f },
            32,
            32,
        )
        val smoothAgain = FeatureEncoder.encode(FloatArray(32 * 32) { 0.4f }, 32, 32)
        val same = meanAbs(smooth.values, smoothAgain.values)
        val different = meanAbs(smooth.values, stripes.values)
        assertTrue(same < different)
    }

    private fun meanAbs(a: FloatArray, b: FloatArray): Float {
        var sum = 0.0
        for (i in a.indices) sum += abs(a[i] - b[i])
        return (sum / a.size).toFloat()
    }
}
