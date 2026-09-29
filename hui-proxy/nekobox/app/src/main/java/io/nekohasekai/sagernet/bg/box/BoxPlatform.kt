package io.nekohasekai.sagernet.bg.box

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.os.Build
import android.os.Process
import android.system.OsConstants
import io.nekohasekai.libbox.*
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface as JNetworkInterface
import java.util.concurrent.ConcurrentHashMap

/** Android platform bridge for SFA libbox. Keep Android plumbing out of the core manager/UI. */
class BoxPlatform(private val service: BoxVpnService) : PlatformInterface {
    private val connectivity = service.getSystemService(ConnectivityManager::class.java)
    private val callbacks = ConcurrentHashMap<InterfaceUpdateListener, ConnectivityManager.NetworkCallback>()

    private fun isUnderlying(network: Network): Boolean {
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private fun underlyingNetwork(): Network? {
        connectivity.activeNetwork?.takeIf(::isUnderlying)?.let { return it }
        return connectivity.allNetworks.firstOrNull(::isUnderlying)
    }

    class Strings(private val values: List<String>) : StringIterator {
        private var index = 0
        override fun hasNext() = index < values.size
        override fun len() = values.size
        override fun next(): String = values[index++]
    }

    private class Interfaces(private val values: List<io.nekohasekai.libbox.NetworkInterface>) : NetworkInterfaceIterator {
        private var index = 0
        override fun hasNext() = index < values.size
        override fun next(): io.nekohasekai.libbox.NetworkInterface = values[index++]
    }

    override fun usePlatformAutoDetectInterfaceControl() = true
    override fun autoDetectInterfaceControl(fd: Int) { service.protect(fd) }
    override fun useProcFS() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    override fun underNetworkExtension() = false
    override fun includeAllNetworks() = false
    override fun usePlatformBridge() = false
    override fun usePlatformShell() = false
    override fun checkPlatformShell() = throw UnsupportedOperationException("Android shell bridge disabled")
    override fun createBridge(options: BridgeOptions?): BridgeSession = throw UnsupportedOperationException("bridge is not enabled")
    override fun lookupSFTPServer(): String = ""
    override fun lookupUser(username: String?): PlatformUser = throw UnsupportedOperationException("platform users unavailable")
    override fun openShellSession(user: PlatformUser?, command: String?, environ: StringIterator?, term: String?, rows: Int, cols: Int): ShellSession =
        throw UnsupportedOperationException("platform shell disabled")
    override fun readSystemSSHHostKey(): String = ""
    override fun tailscaleHostname(): String = Build.MODEL ?: "Android"
    override fun registerMyInterface(name: String?) = Unit
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification?) = Unit
    override fun cancelNotification(identifier: String?, typeID: Int) = Unit
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun clearDNSCache() = Unit

    override fun readWIFIState(): WIFIState? = runCatching {
        @Suppress("DEPRECATION")
        val wifi = service.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)?.connectionInfo
            ?: return@runCatching null
        var ssid = wifi.ssid ?: ""
        if (ssid == "<unknown ssid>") ssid = ""
        if (ssid.length >= 2 && ssid.startsWith('"') && ssid.endsWith('"')) ssid = ssid.substring(1, ssid.length - 1)
        WIFIState(ssid, wifi.bssid ?: "")
    }.getOrNull()

    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String?, sourcePort: Int, destinationAddress: String?, destinationPort: Int): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ConnectionOwner().apply { userId = -1; userName = "" }
        val uid = connectivity?.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        ) ?: Process.INVALID_UID
        return ConnectionOwner().apply {
            userId = uid
            userName = if (uid == Process.INVALID_UID) "" else service.packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
            setAndroidPackageNames(Strings(if (uid == Process.INVALID_UID) emptyList() else service.packageManager.getPackagesForUid(uid)?.toList().orEmpty()))
        }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val javaIfaces = JNetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val result = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        connectivity.allNetworks.forEach { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@forEach
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@forEach
            val lp = connectivity.getLinkProperties(network) ?: return@forEach
            val name = lp.interfaceName ?: return@forEach
            val ji = javaIfaces.firstOrNull { it.name == name } ?: return@forEach
            result += io.nekohasekai.libbox.NetworkInterface().apply {
                this.name = name
                index = ji.index
                mtu = runCatching { ji.mtu }.getOrDefault(1500)
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                var f = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) f = f or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                if (ji.isLoopback) f = f or OsConstants.IFF_LOOPBACK
                if (ji.isPointToPoint) f = f or OsConstants.IFF_POINTOPOINT
                if (ji.supportsMulticast()) f = f or OsConstants.IFF_MULTICAST
                flags = f
                addresses = Strings(ji.interfaceAddresses.mapNotNull { ia -> ia.address.hostAddress?.let { "$it/${ia.networkPrefixLength}" } })
                dnsServer = Strings(lp.dnsServers.mapNotNull(InetAddress::getHostAddress))
                gateway = Strings(lp.routes.filter { it.destination.prefixLength == 0 }.mapNotNull { it.gateway?.hostAddress })
            }
        }
        return Interfaces(result)
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        fun publish(network: Network?) {
            val lp = network?.let(connectivity::getLinkProperties) ?: return
            val caps = connectivity.getNetworkCapabilities(network) ?: return
            listener.updateDefaultInterface(
                lp.interfaceName.orEmpty(),
                runCatching { JNetworkInterface.getByName(lp.interfaceName)?.index ?: 0 }.getOrDefault(0),
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
            )
        }
        publish(underlyingNetwork())
        if (Build.VERSION.SDK_INT >= 24) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = publish(underlyingNetwork())
                override fun onLost(network: Network) = publish(underlyingNetwork())
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = publish(underlyingNetwork())
            }
            callbacks[listener] = cb
            connectivity.registerDefaultNetworkCallback(cb)
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        callbacks.remove(listener)?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
    }

    override fun localDNSTransport(): LocalDNSTransport = object : LocalDNSTransport {
        override fun raw() = false
        override fun exchange(ctx: ExchangeContext?, message: ByteArray?) {
            throw UnsupportedOperationException("raw platform DNS is disabled")
        }
        override fun lookup(ctx: ExchangeContext, network: String?, domain: String) {
            try {
                val addresses = underlyingNetwork()?.getAllByName(domain)?.toList() ?: InetAddress.getAllByName(domain).toList()
                val filtered = when {
                    network?.endsWith("4") == true -> addresses.filterIsInstance<Inet4Address>()
                    network?.endsWith("6") == true -> addresses.filterIsInstance<Inet6Address>()
                    else -> addresses
                }
                ctx.success(filtered.mapNotNull { it.hostAddress }.joinToString("\n"))
            } catch (_: java.net.UnknownHostException) {
                ctx.errorCode(3)
            }
        }
    }

    override fun openTun(options: TunOptions): Int = service.openTunFromCore(options)
}
