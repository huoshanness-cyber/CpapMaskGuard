package app.cpapmaskguard.core

/**
 * 客户端只准备指标遥测。这一行不含图片、不含 base64。
 * 图片捐赠默认关，而且这里不产生上传地址。
 */
object GuardTelemetry {
    const val IMAGE_DONATION_DEFAULT = false

    fun metricLine(
        kind: String,
        epochMs: Long,
        detail: String,
        promptTokens: Int = -1,
        completionTokens: Int = -1,
    ): String {
        return "telemetry kind=$kind t=$epochMs prompt=$promptTokens completion=$completionTokens detail=$detail"
    }
}
