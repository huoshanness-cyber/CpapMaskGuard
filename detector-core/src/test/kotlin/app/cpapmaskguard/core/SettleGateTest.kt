package app.cpapmaskguard.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettleGateTest {
    @Test
    fun firstMaskOffAtEightSecondsDoesNotAlarm() {
        assertFalse(SettleGate.maskOffAlarmAllowed(8_000L))
        assertFalse(SettleGate.maskOffAlarmAllowed(8_000L, maskConfirmed = false))
        assertEquals(52, SettleGate.secondsRemaining(8_000L))
    }

    @Test
    fun confirmedMaskDoesNotOpenTheAlarmInsideTheMinute() {
        assertFalse(SettleGate.maskOffAlarmAllowed(20_000L, maskConfirmed = true))
        assertEquals(40, SettleGate.secondsRemaining(20_000L))
    }

    @Test
    fun minuteEndingArmsEvenWithoutMaskOn() {
        assertFalse(SettleGate.maskOffAlarmAllowed(59_999L))
        assertEquals(1, SettleGate.secondsRemaining(59_999L))
        assertTrue(SettleGate.maskOffAlarmAllowed(60_000L))
        assertTrue(SettleGate.maskOffAlarmAllowed(60_000L, maskConfirmed = false))
        assertEquals(0, SettleGate.secondsRemaining(60_000L))
    }
}
