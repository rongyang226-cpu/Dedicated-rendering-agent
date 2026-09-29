package io.nekohasekai.sagernet.bg

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.core.CoreController
import io.nekohasekai.sagernet.bg.core.CoreStatus
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import android.service.quicksettings.TileService as BaseTileService

@RequiresApi(24)
class TileService : BaseTileService() {
    private val iconIdle by lazy { Icon.createWithResource(this, R.drawable.ic_service_idle) }
    private val iconBusy by lazy { Icon.createWithResource(this, R.drawable.ic_service_busy) }
    private val iconConnected by lazy { Icon.createWithResource(this, R.drawable.ic_service_active) }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(this::toggle) else toggle()
    }

    private fun refresh() = updateTile(CoreController.status(this))

    private fun updateTile(status: CoreStatus) {
        qsTile?.apply {
            when (status.state) {
                CoreStatus.State.STARTING -> { icon = iconBusy; state = Tile.STATE_ACTIVE }
                CoreStatus.State.RUNNING -> { icon = iconConnected; state = Tile.STATE_ACTIVE }
                CoreStatus.State.STOPPING -> { icon = iconBusy; state = Tile.STATE_UNAVAILABLE }
                CoreStatus.State.ERROR -> { icon = iconIdle; state = Tile.STATE_UNAVAILABLE }
                CoreStatus.State.STOPPED -> { icon = iconIdle; state = Tile.STATE_INACTIVE }
            }
            label = if (status.active) CoreController.selected.displayName else getString(R.string.app_name)
            if (Build.VERSION.SDK_INT >= 29) subtitle = status.message.takeIf { it.isNotBlank() }
            updateTile()
        }
    }

    private fun toggle() {
        if (CoreController.status(this).active) {
            CoreController.stopAll(this)
            refresh()
            return
        }
        if (CoreController.needsVpnPermission(this)) {
            openPermissionActivity()
            return
        }
        runOnDefaultDispatcher {
            runCatching { CoreController.startSelectedAuthorized(this@TileService) }
                .onFailure { Logs.w(it) }
            runOnMainDispatcher { refresh() }
        }
    }

    @Suppress("DEPRECATION")
    private fun openPermissionActivity() {
        val intent = Intent(this, VpnRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this, 4102, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
