package app.cpapmaskguard

import java.util.Locale

internal const val LOG_TAG = "CpapMaskGuard"

internal fun Float.twoDigits(): String =
    if (this < 0f) "—" else String.format(Locale.US, "%.2f", this)
