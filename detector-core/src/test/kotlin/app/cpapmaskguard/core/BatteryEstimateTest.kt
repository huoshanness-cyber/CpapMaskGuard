package app.cpapmaskguard.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatteryEstimateTest {
    @Test
    fun lowBatterySuggestsPlugIn() {
        val e = BatteryEstimate.estimate(levelPercent = 25, charging = false)
        assertTrue(e.suggestPlugIn)
        assertTrue(e.hoursConservative < BatteryEstimate.TARGET_NIGHT_HOURS)
    }

    @Test
    fun chargingNeverSuggests() {
        val e = BatteryEstimate.estimate(levelPercent = 10, charging = true)
        assertFalse(e.suggestPlugIn)
    }

    @Test
    fun fullBatteryDoesNotSuggest() {
        val e = BatteryEstimate.estimate(levelPercent = 100, charging = false)
        assertFalse(e.suggestPlugIn)
        assertTrue(e.hoursConservative >= BatteryEstimate.TARGET_NIGHT_HOURS)
    }
}
