package app.cpapmaskguard.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AimGateTest {
    @Test
    fun aLargeEnoughFaceCountsAsAimed() {
        val verdict = AimGate.evaluate(AimGate.Sample(faceWidthFraction = 0.2f))
        assertTrue(verdict.valid)
        assertEquals(AimGate.Reason.FACE, verdict.reason)
    }

    @Test
    fun aTinyFaceOnABlackFrameIsRejectedAsBlack() {
        val verdict = AimGate.evaluate(
            AimGate.Sample(faceWidthFraction = 0.05f, centerMean = 0.01f),
        )
        assertFalse(verdict.valid)
        assertEquals(AimGate.Reason.BLACK, verdict.reason)
    }

    @Test
    fun aFullFaceMaskWithoutAFaceStillPassesWhenUncertain() {
        val verdict = AimGate.evaluate(
            AimGate.Sample(faceWidthFraction = 0.02f, centerMean = 0.35f, centerVariance = 0.001f, centerEdge = 0.008f),
        )
        assertTrue(verdict.valid)
        assertEquals(AimGate.Reason.UNCERTAIN, verdict.reason)
    }

    @Test
    fun aPersonOrHeadBoxCountsAsAimed() {
        val verdict = AimGate.evaluate(AimGate.Sample(personAreaFraction = 0.08f))
        assertTrue(verdict.valid)
        assertEquals(AimGate.Reason.PERSON, verdict.reason)
    }

    @Test
    fun aCalibratedSceneCountsEvenWhenTheFrameIsDark() {
        val verdict = AimGate.evaluate(
            AimGate.Sample(sceneMatchesBedside = true, centerMean = 0.01f, centerVariance = 0f),
        )
        assertTrue(verdict.valid)
        assertEquals(AimGate.Reason.SCENE, verdict.reason)
    }

    @Test
    fun aSoftPillowAtTheThresholdStillCounts() {
        val verdict = AimGate.evaluate(
            AimGate.Sample(centerMean = 0.20f, centerVariance = 0.0018f, centerEdge = 0.012f),
        )
        assertTrue(verdict.valid)
        assertEquals(AimGate.Reason.BEDSIDE, verdict.reason)
    }

    @Test
    fun aLitTexturedCenterCountsAsPillow() {
        val verdict = AimGate.evaluate(
            AimGate.Sample(centerMean = 0.28f, centerVariance = 0.02f, centerEdge = 0.06f),
        )
        assertTrue(verdict.valid)
        assertEquals(AimGate.Reason.BEDSIDE, verdict.reason)
    }

    @Test
    fun brightTexturedFramesAreAllowedEvenWithoutAFace() {
        val verdict = AimGate.evaluate(
            AimGate.Sample(centerMean = 0.97f, centerVariance = 0.03f, centerEdge = 0.05f),
        )
        assertTrue(verdict.valid)
        assertEquals(AimGate.Reason.UNCERTAIN, verdict.reason)
    }

    @Test
    fun blackWhiteAndSevereBlurAreTheOnlyRejections() {
        val black = AimGate.evaluate(AimGate.Sample(centerMean = 0.01f, centerVariance = 0.0001f, centerEdge = 0.001f))
        val blur = AimGate.evaluate(AimGate.Sample(centerMean = 0.4f, centerVariance = 0.0002f, centerEdge = 0.004f))
        val white = AimGate.evaluate(AimGate.Sample(centerMean = 0.97f, centerVariance = 0.0001f, centerEdge = 0.001f))
        assertFalse(black.valid)
        assertEquals(AimGate.Reason.BLACK, black.reason)
        assertFalse(blur.valid)
        assertEquals(AimGate.Reason.BLUR, blur.reason)
        assertFalse(white.valid)
        assertEquals(AimGate.Reason.WHITE, white.reason)
    }
}
