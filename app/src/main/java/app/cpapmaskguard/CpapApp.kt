package app.cpapmaskguard

import android.app.Application
import android.os.Build
import app.cpapmaskguard.notify.Notifications
import app.cpapmaskguard.util.AppLogger

class CpapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.i(
            LOG_TAG,
            "启动 device=${Build.MANUFACTURER} ${Build.MODEL} SDK=${Build.VERSION.SDK_INT} " +
                "brand=${Build.BRAND} product=${Build.PRODUCT}",
        )
        Notifications.createChannels(this)
    }
}
