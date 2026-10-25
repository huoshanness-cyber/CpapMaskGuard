package app.cpapmaskguard.settings

/**
 * 补光策略（互斥两档）。
 * - [FRONT_SCREEN]：前摄 + 监测时全白屏，用窗口亮度补光（推荐，尤其手电筒不可调强弱的机型）。
 * - [REAR_AMBIENT]：后摄 + 默认关手电筒，用户自备台灯等光源。
 */
enum class FillLightMode {
    FRONT_SCREEN,
    REAR_AMBIENT,
    ;

    val useFrontCamera: Boolean get() = this == FRONT_SCREEN

    companion object {
        fun fromStorage(raw: String?): FillLightMode = when (raw) {
            "REAR_AMBIENT", "rear", "back" -> REAR_AMBIENT
            "FRONT_SCREEN", "front" -> FRONT_SCREEN
            else -> FRONT_SCREEN
        }
    }
}
