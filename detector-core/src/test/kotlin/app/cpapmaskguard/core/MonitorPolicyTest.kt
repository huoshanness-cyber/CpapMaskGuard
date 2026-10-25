package app.cpapmaskguard.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonitorPolicyTest {
    @Test
    fun threeGigabytePhonesDefaultToLowEnd() {
        val threeGb = 3L * 1024 * 1024 * 1024
        assertTrue(MonitorPolicy.defaultLowEnd(threeGb))
        assertFalse(MonitorPolicy.defaultLowEnd(6L * 1024 * 1024 * 1024))
    }

    @Test
    fun lowEndIntervalStaysBetweenTenAndFifteen() {
        assertEquals(10..15, MonitorPolicy.intervalRange(lowEnd = true))
        assertEquals(12, MonitorPolicy.defaultInterval(lowEnd = true))
        assertEquals(10, MonitorPolicy.clampInterval(lowEnd = true, seconds = 5))
        assertEquals(15, MonitorPolicy.clampInterval(lowEnd = true, seconds = 30))
    }

    @Test
    fun standardIntervalStaysBetweenFiveAndTen() {
        assertEquals(5..10, MonitorPolicy.intervalRange(lowEnd = false))
        assertEquals(8, MonitorPolicy.defaultInterval(lowEnd = false))
        assertEquals(5, MonitorPolicy.clampInterval(lowEnd = false, seconds = 1))
        assertEquals(10, MonitorPolicy.clampInterval(lowEnd = false, seconds = 12))
    }

    @Test
    fun lowEndResolutionIsVgaClass() {
        assertEquals(FrameSize(640, 480), MonitorPolicy.defaultResolution(lowEnd = true))
        assertEquals(FrameSize(1280, 720), MonitorPolicy.defaultResolution(lowEnd = false))
        assertEquals(FrameSize(640, 480), MonitorPolicy.sanitizeResolution(640, 480))
        assertEquals(FrameSize(1280, 720), MonitorPolicy.sanitizeResolution(1920, 1080))
    }
}
