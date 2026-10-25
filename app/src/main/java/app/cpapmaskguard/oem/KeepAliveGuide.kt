package app.cpapmaskguard.oem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import app.cpapmaskguard.LOG_TAG
import java.util.Locale

/**
 * Keep-alive helpers: standard Android APIs first.
 * Xiaomi / MIUI / HyperOS deep-links are optional extras only — never required
 * for install or for flashlight / camera / notifications on other brands.
 */
object KeepAliveGuide {
    fun isXiaomiFamily(): Boolean {
        val blob = listOf(Build.MANUFACTURER, Build.BRAND, Build.DISPLAY, Build.MODEL)
            .joinToString(" ")
            .lowercase(Locale.US)
        return listOf("xiaomi", "redmi", "poco", "hyperos", "miui").any { blob.contains(it) }
    }

    fun deviceLine(): String {
        return "${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.DISPLAY}"
    }

    fun ramLine(context: Context): String {
        val info = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(info)
        val gb = info.totalMem / 1024.0 / 1024.0 / 1024.0
        return "约 ${String.format(Locale.US, "%.1f", gb)} GB 内存"
    }

    /** Generic: request ignore battery optimizations, then settings list, then app details. */
    fun openBatteryOptimization(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        if (power?.isIgnoringBatteryOptimizations(context.packageName) != true) {
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            if (tryStart(context, request)) return
        }
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (!tryStart(context, list)) openAppDetails(context)
    }

    /**
     * Generic autostart / background guidance: open application details
     * (where most OEMs expose autostart, battery, and background controls).
     * Does not require MIUI SecurityCenter.
     */
    fun openAutostartGuidance(context: Context): Boolean {
        return openAppDetails(context)
    }

    /**
     * Optional Xiaomi-only helper. Safe no-op on other brands (returns false).
     * Prefer [openAutostartGuidance] for all devices.
     */
    fun openMiuiAutoStart(context: Context): Boolean {
        if (!isXiaomiFamily()) return false
        val intent = Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        )
        return tryStart(context, intent)
    }

    /**
     * Optional Xiaomi-only battery / hidden-apps page. Not required on other brands.
     * Call only after generic [openBatteryOptimization] if the user still needs it.
     */
    fun openMiuiBattery(context: Context): Boolean {
        if (!isXiaomiFamily()) return false
        val hidden = Intent().setComponent(
            ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            ),
        ).apply {
            putExtra("package_name", context.packageName)
            putExtra("package_label", "面罩守护")
        }
        if (tryStart(context, hidden)) return true
        val power = Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.powercenter.PowerSettings",
            ),
        )
        return tryStart(context, power)
    }

    fun openFullScreenSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        return tryStart(context, intent)
    }

    fun openDndOrSound(context: Context) {
        val zen = Intent("android.settings.ZEN_MODE_SETTINGS")
        if (!tryStart(context, zen)) {
            tryStart(context, Intent(Settings.ACTION_SOUND_SETTINGS))
        }
    }

    fun openAppDetails(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        return tryStart(context, intent)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) return false
            context.startActivity(intent)
            true
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "打不开系统页面", error)
            false
        }
    }
}