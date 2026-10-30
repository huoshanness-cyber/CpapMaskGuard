package app.cpapmaskguard.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnoreHeuristicsTest {
    @Test
    fun silenceIsNotSnore() {
        val pcm = ShortArray(1600) { 0 }
        assertFalse(SnoreHeuristics.analyze(pcm).snoreLike)
    }

    @Test
    fun lowToneLoudLooksSnoreLike() {
        // ~120 Hz at 16 kHz, moderate amplitude — snore-ish
        val pcm = ShortArray(1600) { i ->
            (sin(2.0 * PI * 120.0 * i / 16000.0) * 6000).toInt().toShort()
        }
        assertTrue(SnoreHeuristics.analyze(pcm).snoreLike)
    }

    @Test
    fun highZcrNoiseNotSnore() {
        val pcm = ShortArray(1600) { i -> if (i % 2 == 0) 8000 else (-8000).toShort() }
        assertFalse(SnoreHeuristics.analyze(pcm).snoreLike)
    }

    @Test
    fun trackerConfirmsAfterStreak() {
        val t = SnoreHeuristics.Tracker(confirmNeed = 3, holdNeed = 2, history = 6)
        var elapsed = 0L
        assertFalse(t.onWindow(true, elapsed))
        elapsed += 500
        assertFalse(t.onWindow(true, elapsed))
        elapsed += 500
        assertTrue(t.onWindow(true, elapsed))
        assertTrue(t.confirmedSinceElapsedMs >= 0L)
    }
}

class SnoreFusionTest {
    @Test
    fun cameraAlarmWins() {
        val r = SnoreFusion.fuse(true, FrameVote.CLOSER_TO_MASK_OFF, false, 0, "cam")
        assertTrue(r.shouldAlarm)
        assertEquals("cam", r.reason)
    }

    @Test
    fun maskOffStillAlarmsWhenSnoreFlagIsOff() {
        val r = SnoreFusion.fuse(
            true,
            FrameVote.CLOSER_TO_MASK_OFF,
            true,
            SnoreFusion.AUDIO_ALARM_MS_WHEN_ON,
            "云端：面罩脱落/未戴",
        )
        assertTrue(r.shouldAlarm)
        assertEquals("云端：面罩脱落/未戴", r.reason)
        assertFalse(r.audioAssisted)
        assertFalse(r.reason.contains("音频确认打呼"))
    }

    @Test
    fun audioAloneDoesNotAlarmOrBecomeTheReasonByDefault() {
        val r = SnoreFusion.fuse(
            false,
            FrameVote.UNCERTAIN,
            true,
            SnoreFusion.AUDIO_ALARM_MS_WHEN_NOT_ON,
            "cam",
        )
        assertFalse(r.shouldAlarm)
        assertEquals("cam", r.reason)
        assertFalse(r.reason.contains("音频确认打呼"))
        assertFalse(SnoreFusion.AUDIO_ALARM_ENABLED)
    }

    @Test
    fun audioAloneAfterDwellWhenFlagOn() {
        val r = SnoreFusion.fuse(
            false,
            FrameVote.UNCERTAIN,
            true,
            SnoreFusion.AUDIO_ALARM_MS_WHEN_NOT_ON,
            "cam",
            audioAlarmEnabled = true,
        )
        assertTrue(r.shouldAlarm)
        assertTrue(r.audioAssisted)
        assertTrue(r.reason.contains("音频确认打呼"))
    }

    @Test
    fun audioNeedsLongerWhenCameraSaysOnAndFlagOn() {
        val early = SnoreFusion.fuse(
            false,
            FrameVote.CLOSER_TO_MASK_ON,
            true,
            SnoreFusion.AUDIO_ALARM_MS_WHEN_NOT_ON,
            "cam",
            audioAlarmEnabled = true,
        )
        assertFalse(early.shouldAlarm)
        val late = SnoreFusion.fuse(
            false,
            FrameVote.CLOSER_TO_MASK_ON,
            true,
            SnoreFusion.AUDIO_ALARM_MS_WHEN_ON,
            "cam",
            audioAlarmEnabled = true,
        )
        assertTrue(late.shouldAlarm)
    }
}
