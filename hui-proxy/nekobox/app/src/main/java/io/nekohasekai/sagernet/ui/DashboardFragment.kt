package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.os.SystemClock
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.core.CoreController
import io.nekohasekai.sagernet.bg.core.CoreStatus
import io.nekohasekai.sagernet.utils.HuiVisuals
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

class DashboardFragment : ToolbarFragment(R.layout.layout_dashboard) {
    private var ticker: Runnable? = null
    private var cachedIp = "—"
    private var lastIpRefresh = 0L
    private var ipRefreshRunning = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.title = "仪表盘"
        val nodes = view.findViewById<View>(R.id.dashboard_nodes)
        val connect = view.findViewById<View>(R.id.dashboard_connect)
        HuiVisuals.applyLiquidPress(nodes)
        HuiVisuals.applyLiquidPress(connect)
        nodes.setOnClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_configuration)
        }
        connect.setOnClickListener {
            (activity as? MainActivity)?.toggleServiceFromUi()
        }

        ticker = object : Runnable {
            override fun run() {
                if (isAdded) updateDashboard(view)
                view.postDelayed(this, 1000L)
            }
        }.also { view.post(it) }
    }

    private fun updateDashboard(view: View) {
        val context = view.context
        val status = (activity as? MainActivity)?.coreStatusSnapshot() ?: CoreStatus()
        val engine = CoreController.selected
        val stateText = when (status.state) {
            CoreStatus.State.STARTING -> "连接中"
            CoreStatus.State.RUNNING -> "已连接"
            CoreStatus.State.STOPPING -> "断开中"
            CoreStatus.State.ERROR -> "异常"
            else -> "未连接"
        }
        view.findViewById<TextView>(R.id.dashboard_status).text = stateText
        view.findViewById<TextView>(R.id.dashboard_connect).apply {
            text = when (status.state) {
                CoreStatus.State.RUNNING, CoreStatus.State.STARTING -> "断开 · ${engine.displayName}"
                CoreStatus.State.STOPPING -> "正在断开"
                else -> "连接 · ${engine.displayName}"
            }
            isSelected = status.active
            isEnabled = status.state != CoreStatus.State.STOPPING
        }
        view.findViewById<TextView>(R.id.dashboard_speed).text =
            "↑ ${Formatter.formatFileSize(context, status.txRate)}/s    ↓ ${Formatter.formatFileSize(context, status.rxRate)}/s"
        view.findViewById<TextView>(R.id.dashboard_total).text =
            "↑ ${Formatter.formatFileSize(context, status.txTotal)}    ↓ ${Formatter.formatFileSize(context, status.rxTotal)}"
        view.findViewById<TextView>(R.id.dashboard_engine).text =
            if (CoreController.isAutoMode()) "自动 · ${engine.displayName}" else engine.displayName
        view.findViewById<TextView>(R.id.dashboard_version).text =
            if (status.state == CoreStatus.State.ERROR) status.message
            else status.version.ifBlank { status.message }
        view.findViewById<TextView>(R.id.dashboard_ip).text = cachedIp
        refreshIpIfNeeded(view)
    }

    private fun refreshIpIfNeeded(view: View) {
        val now = SystemClock.elapsedRealtime()
        if (ipRefreshRunning || now - lastIpRefresh < 15_000L) return
        ipRefreshRunning = true
        lastIpRefresh = now
        viewLifecycleOwner.lifecycleScope.launch {
            cachedIp = withContext(Dispatchers.IO) { localIpv4() }
            ipRefreshRunning = false
            if (isAdded) view.findViewById<TextView>(R.id.dashboard_ip).text = cachedIp
        }
    }

    private fun localIpv4(): String = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (!network.isUp || network.isLoopback) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    return@runCatching address.hostAddress ?: "—"
                }
            }
        }
        "—"
    }.getOrDefault("—")

    override fun onDestroyView() {
        ticker?.let { view?.removeCallbacks(it) }
        ticker = null
        super.onDestroyView()
    }
}
