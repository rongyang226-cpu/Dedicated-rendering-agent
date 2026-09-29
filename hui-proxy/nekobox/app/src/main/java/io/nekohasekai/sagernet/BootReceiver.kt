package io.nekohasekai.sagernet

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.bg.core.CoreController
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class BootReceiver : BroadcastReceiver() {
    companion object {
        private val componentName by lazy { ComponentName(app, BootReceiver::class.java) }
        var enabled: Boolean
            get() = app.packageManager.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            set(value) = app.packageManager.setComponentEnabledSetting(
                componentName, if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!DataStore.persistAcrossReboot) {
            enabled = false
            return
        }

        val doStart = when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> false
            else -> Build.VERSION.SDK_INT < 24 || SagerNet.user.isUserUnlocked
        } && CoreController.hasUsableConfig(context)

        val pending = goAsync()
        runOnDefaultDispatcher {
            try {
                SubscriptionUpdater.reconfigureUpdater()
                if (doStart && !CoreController.needsVpnPermission(context)) {
                    CoreController.startSelectedAuthorized(context)
                }
            } catch (e: Throwable) {
                Logs.w(e)
            } finally {
                pending.finish()
            }
        }
    }
}
