package app.cpapmaskguard.core

/**
 * 点「开始」时的保守可监测时长估算（小时）。
 * 假设后摄间隔抓拍 + 弱光常亮大约每小时耗电 [PERCENT_PER_HOUR]%，
 * 并预留 [RESERVE_PERCENT]% 电量，不够一整晚只建议插电，不强制。
 */
object BatteryEstimate {
    /** 摄像 + 弱光常亮的保守耗电（百分电量 / 小时）。 */
    const val PERCENT_PER_HOUR = 10f

    /** 预留电量，避免关机。 */
    const val RESERVE_PERCENT = 15f

    /** 一晚目标时长（小时），用于文案对比。 */
    const val TARGET_NIGHT_HOURS = 7f

    data class Estimate(
        val levelPercent: Int,
        val hoursConservative: Float,
        val suggestPlugIn: Boolean,
        val message: String,
    )

    fun estimate(levelPercent: Int, charging: Boolean): Estimate {
        val usable = (levelPercent - RESERVE_PERCENT).coerceAtLeast(0f)
        val hours = if (PERCENT_PER_HOUR <= 0f) 0f else usable / PERCENT_PER_HOUR
        val suggest = !charging && hours < TARGET_NIGHT_HOURS
        val hoursText = String.format("%.1f", hours)
        val message = when {
            charging -> "正在充电。按当前保守估计，插着电可以整晚监测。"
            suggest ->
                "当前电量约 $levelPercent%。按摄像+弱光常亮的保守估计，大约还能监测 $hoursText 小时（已预留约 ${RESERVE_PERCENT.toInt()}%）。不够一整晚，建议插电；仍可开始。"
            else ->
                "当前电量约 $levelPercent%。按保守估计大约还能监测 $hoursText 小时（已预留约 ${RESERVE_PERCENT.toInt()}%）。"
        }
        return Estimate(levelPercent, hours, suggest, message)
    }
}
