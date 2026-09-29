package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.core.CoreController
import io.nekohasekai.sagernet.bg.core.CoreStatus
import io.nekohasekai.sagernet.utils.HuiVisuals
import java.net.Inet4Address
import java.net.NetworkInterface

class DashboardFragment : ToolbarFragment(R.layout.layout_dashboard) {
    private var ticker: Runnable? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.title = "仪表盘"
        val nodes = view.findViewById<View>(R.id.dashboard_nodes)
        HuiVisuals.applyLiquidPress(nodes)
        nodes.setOnClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_configuration)
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
        val status = CoreController.status(context.applicationContext)
        val engine = CoreController.selected
        view.findViewById<TextView>(R.id.dashboard_status).text = when (status.state) {
            CoreStatus.State.STARTING -> "连接中"
            CoreStatus.State.RUNNING -> "已连接"
            CoreStatus.State.STOPPING -> "断开中"
            CoreStatus.State.ERROR -> "异常"
            else -> "未连接"
        }
        view.findViewById<TextView>(R.id.dashboard_speed).text =
            "↑ ${Formatter.formatFileSize(context, status.txRate)}/s    ↓ ${Formatter.formatFileSize(context, status.rxRate)}/s"
        view.findViewById<TextView>(R.id.dashboard_total).text =
            "↑ ${Formatter.formatFileSize(context, status.txTotal)}    ↓ ${Formatter.formatFileSize(context, status.rxTotal)}"
        view.findViewById<TextView>(R.id.dashboard_engine).text = engine.displayName
        view.findViewById<TextView>(R.id.dashboard_version).text = status.version.ifBlank { status.message }
        view.findViewById<TextView>(R.id.dashboard_ip).text = localIpv4()
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
