package app.cpapmaskguard.core

/**
 * 开始守护后的躺好窗口。
 * 02:16 那次：首帧 NO_PERSON，约 8 秒后第一张 MASK_OFF 就响（连续确认只要 1 帧），
 * 人还坐在床边理管路，全程没有 MASK_ON。
 */
object SettleGate {
    const val WINDOW_MS = 60_000L

    /** 缓冲结束前禁止面罩脱落报警。确认戴罩也不能把这一分钟提前结束。 */
    fun maskOffAlarmAllowed(elapsedMs: Long, maskConfirmed: Boolean = false): Boolean {
        if (elapsedMs < WINDOW_MS) return false
        return elapsedMs >= WINDOW_MS || (maskConfirmed && elapsedMs >= WINDOW_MS)
    }

    fun secondsRemaining(elapsedMs: Long): Int {
        if (elapsedMs >= WINDOW_MS) return 0
        val left = WINDOW_MS - elapsedMs
        return ((left + 999L) / 1000L).toInt().coerceAtLeast(1)
    }
}
