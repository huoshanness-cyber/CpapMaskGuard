package app.cpapmaskguard.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuardTelemetryTest {
    @Test
    fun metricLineCarriesTokensAndNoImage() {
        val line = GuardTelemetry.metricLine("round", 1_700_000_000_000L, "mask_on", 12, 3)
        assertTrue(line.contains("kind=round"))
        assertTrue(line.contains("prompt=12"))
        assertTrue(line.contains("completion=3"))
        assertFalse(line.contains("jpeg"))
        assertFalse(line.contains("base64"))
        assertFalse(line.contains("image"))
        assertFalse(GuardTelemetry.IMAGE_DONATION_DEFAULT)
    }
}
