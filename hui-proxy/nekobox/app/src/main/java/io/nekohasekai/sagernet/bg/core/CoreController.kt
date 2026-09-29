package io.nekohasekai.sagernet.bg.core

import android.content.Context
import android.net.VpnService
import kotlinx.coroutines.delay
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.box.BoxCoreManager
import io.nekohasekai.sagernet.bg.meta.MetaCoreManager
import io.nekohasekai.sagernet.database.DataStore

/** Single entry point for UI code. Core-specific details stay behind this boundary. */
object CoreController {
    val selected: CoreEngine get() = CoreEngine.fromId(DataStore.huiCoreEngine)

    fun isSelected(engine: CoreEngine): Boolean = selected == engine

    fun normalizeSelection() {
        val normalized = selected.id
        if (DataStore.huiCoreEngine != normalized) DataStore.huiCoreEngine = normalized
    }

    fun status(context: Context): CoreStatus = when (selected) {
        CoreEngine.BOX -> BoxCoreManager.readStatus(context)
        CoreEngine.META -> MetaCoreManager.readCoreStatus(context)
    }

    fun hasUsableConfig(context: Context): Boolean = when (selected) {
        CoreEngine.BOX -> BoxCoreManager.hasPreparedConfig(context) || DataStore.selectedProxy > 0L
        CoreEngine.META -> MetaCoreManager.hasConfig(context)
    }

    /** Prefer the user's selection, but never strand Connect on an empty core. */
    fun ensureUsableSelection(context: Context): CoreEngine {
        val current = selected
        val currentUsable = when (current) {
            CoreEngine.BOX -> BoxCoreManager.hasPreparedConfig(context) || DataStore.selectedProxy > 0L
            CoreEngine.META -> MetaCoreManager.hasConfig(context)
        }
        if (currentUsable) return current

        val fallback = when (current) {
            CoreEngine.META -> if (BoxCoreManager.hasPreparedConfig(context) || DataStore.selectedProxy > 0L) CoreEngine.BOX else current
            CoreEngine.BOX -> if (MetaCoreManager.hasConfig(context)) CoreEngine.META else current
        }
        if (fallback != current) DataStore.huiCoreEngine = fallback.id
        return fallback
    }


    suspend fun prepareSelected(context: Context): String = when (selected) {
        CoreEngine.BOX -> BoxCoreManager.prepareSelectedProfile(context)
        CoreEngine.META -> {
            require(MetaCoreManager.hasConfig(context)) { "先从添加菜单导入 Meta / Mihomo YAML 配置" }
            "Meta"
        }
    }

    fun startPrepared(context: Context) {
        when (selected) {
            CoreEngine.BOX -> BoxCoreManager.start(context)
            CoreEngine.META -> MetaCoreManager.start(context)
        }
    }

    fun needsVpnPermission(context: Context): Boolean = VpnService.prepare(context) != null

    suspend fun startSelectedAuthorized(context: Context, settleMillis: Long = 180L) {
        normalizeSelection()
        ensureUsableSelection(context)
        stopAll(context)
        if (settleMillis > 0) delay(settleMillis)
        prepareSelected(context)
        check(!needsVpnPermission(context)) { "VPN_PERMISSION_REQUIRED" }
        startPrepared(context)
    }

    suspend fun reloadSelected(context: Context, settleMillis: Long = 220L) {
        startSelectedAuthorized(context, settleMillis)
    }

    suspend fun toggleSelected(context: Context) {
        if (status(context).active) stopAll(context) else startSelectedAuthorized(context)
    }

    suspend fun restartAuthorized(context: Context, settleMillis: Long = 300L) {
        startSelectedAuthorized(context, settleMillis)
    }

    fun stopAll(context: Context) {
        // Keep legacy stop during migration so an older :bg process can never hold the VPN.
        runCatching { SagerNet.stopService() }
        runCatching { BoxCoreManager.stop(context) }
        runCatching { MetaCoreManager.stop(context) }
    }
}
