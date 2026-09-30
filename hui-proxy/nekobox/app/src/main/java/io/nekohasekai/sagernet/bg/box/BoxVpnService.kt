package io.nekohasekai.sagernet.bg.box

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.IpPrefix
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ServiceCompat
import go.Seq
import io.nekohasekai.libbox.*
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.core.CoreBuildInfo
import io.nekohasekai.sagernet.bg.core.CoreNativeOverride
import io.nekohasekai.sagernet.ui.MainActivity
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Modern sing-box/SFA engine. Runs only in :box so its Go runtime never shares a process with legacy libcore. */
class BoxVpnService : VpnService(), CommandServerHandler, CommandClientHandler {
    companion object {
        const val START = "com.yingbao.hui.BOX_START"
        const val STOP = "com.yingbao.hui.BOX_STOP"
        private const val CHANNEL = "hui-box-vpn"
        private const val NOTIFICATION_ID = 46
        private val LIBBOX_READY = AtomicBoolean(false)
    }

    private var wanted = false
    private val stopping = AtomicBoolean(false)
    private var worker: Thread? = null
    private var heartbeat: Thread? = null
    @Volatile private var lastTxRate = 0L
    @Volatile private var lastRxRate = 0L
    @Volatile private var lastTxTotal = 0L
    @Volatile private var lastRxTotal = 0L
    private var tun: ParcelFileDescriptor? = null
    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null
    private lateinit var platform: BoxPlatform
    private var systemProxyAvailable = false
    private var systemProxyEnabled = false
    // Do not call VpnService.Builder.allowBypass(): that is not the LAN-bypass setting
    // and may allow traffic to escape the VPN. LAN bypass is expressed in TUN routes.
    private var proxyApps = false
    private var bypassApps = true
    private var individualApps = emptyList<String>()
    private var version = CoreBuildInfo.BOX_CORE

    private fun home() = File(filesDir, "box")
    private fun configFile() = File(home(), "profiles/active/config.json")
    private fun statusFile() = File(home(), "status.json")

    override fun onCreate() {
        super.onCreate()
        platform = BoxPlatform(this)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "绘 · Box", NotificationManager.IMPORTANCE_LOW)
            )
        }
        writeStatus("STOPPED", "未连接")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> {
                wanted = false
                stopCurrent(false)
                return START_NOT_STICKY
            }
            START -> Unit
            else -> return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_NOT_STICKY
        stopping.set(false)
        proxyApps = intent.getBooleanExtra("proxyApps", false)
        bypassApps = intent.getBooleanExtra("bypassApps", true)
        individualApps = intent.getStringArrayListExtra("individualApps")?.filter { it.isNotBlank() }.orEmpty()
        wanted = true
        startForeground(NOTIFICATION_ID, notification("正在启动 Box"))
        writeStatus("STARTING", "正在加载 ${CoreBuildInfo.BOX_CORE}")
        worker = Thread(::startCore, "hui-box-core").apply { start() }
        return START_NOT_STICKY
    }

    private fun ensureLibbox() {
        if (LIBBOX_READY.compareAndSet(false, true)) {
            Seq.setContext(applicationContext)
            val base = File(home(), "runtime/base").apply { mkdirs() }
            val working = File(home(), "runtime/working").apply { mkdirs() }
            val temp = File(cacheDir, "box").apply { mkdirs() }
            Libbox.setup(SetupOptions().apply {
                setBasePath(base.absolutePath)
                setWorkingPath(working.absolutePath)
                setTempPath(temp.absolutePath)
                setFixAndroidStack(false)
                setLogMaxLines(3000)
                setDebug(BuildConfig.DEBUG)
                setCrashReportSource("HuiBox")
                setAppVersion(BuildConfig.VERSION_CODE.toString())
                setAppMarketingVersion(BuildConfig.VERSION_NAME)
                setOomKillerEnabled(false)
                setOomKillerDisabled(true)
                setPowerReportEnabled(false)
            })
        }
    }

    private fun startCore() {
        var preserveError = false
        try {
            if (prepare(this) != null) error("VPN 授权已失效，请重新授权")
            val file = configFile()
            require(file.isFile && file.length() > 1L) { "请先选择节点并准备 Box 配置" }
            val config = file.readText()
            ensureLibbox()
            version = runCatching { Libbox.version() }.getOrDefault(CoreBuildInfo.BOX_CORE)
            Libbox.checkConfig(config)
            if (!wanted) return

            commandServer = Libbox.newCommandServer(this, platform).also { it.start() }
            commandServer!!.startOrReloadService(config, createOverrides())
            if (!wanted) return
            startStatusClient()
            writeStatus("RUNNING", "Box 已连接 · $version")
            startHeartbeat()
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification("Box 已连接 · $version"))
        } catch (t: Throwable) {
            preserveError = true
            Log.e("HuiBox", "core failure", t)
            writeStatus("ERROR", readable(t))
            wanted = false
        } finally {
            if (!wanted) stopCurrent(preserveError)
        }
    }

    private fun createOverrides() = OverrideOptions().apply {
        setAutoRedirect(false)
        if (proxyApps && individualApps.isNotEmpty()) {
            val packages = individualApps.filter { it != packageName }
            if (bypassApps) setExcludePackage(BoxPlatform.Strings(packages))
            else setIncludePackage(BoxPlatform.Strings((packages + packageName).distinct()))
        }
    }

    private fun startStatusClient() {
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            setStatusInterval(1_000_000_000L)
        }
        commandClient = Libbox.newCommandClient(this, options).also { it.connect() }
    }

    internal fun openTunFromCore(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing VPN permission")
        tun?.close(); tun = null
        val builder = Builder().setSession("绘 · Box").setMtu(options.mtu.coerceIn(1280, 9000))
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

        val v4 = collectRoutes(options.inet4Address)
        val v6 = collectRoutes(options.inet6Address)
        v4.forEach { builder.addAddress(it.address(), it.prefix()) }
        v6.forEach { builder.addAddress(it.address(), it.prefix()) }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                collectStrings(options.dnsServerAddress).forEach(builder::addDnsServer)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                val r4 = collectRoutes(options.inet4RouteAddress)
                val r6 = collectRoutes(options.inet6RouteAddress)
                if (r4.isEmpty() && v4.isNotEmpty()) builder.addRoute(IpPrefix(java.net.InetAddress.getByName("0.0.0.0"), 0)) else r4.forEach { builder.addRoute(IpPrefix(java.net.InetAddress.getByName(it.address()), it.prefix())) }
                if (r6.isEmpty() && v6.isNotEmpty()) builder.addRoute(IpPrefix(java.net.InetAddress.getByName("::"), 0)) else r6.forEach { builder.addRoute(IpPrefix(java.net.InetAddress.getByName(it.address()), it.prefix())) }
                collectRoutes(options.inet4RouteExcludeAddress).forEach { builder.excludeRoute(IpPrefix(java.net.InetAddress.getByName(it.address()), it.prefix())) }
                collectRoutes(options.inet6RouteExcludeAddress).forEach { builder.excludeRoute(IpPrefix(java.net.InetAddress.getByName(it.address()), it.prefix())) }
            } else {
                collectRoutes(options.inet4RouteRange).forEach { builder.addRoute(it.address(), it.prefix()) }
                collectRoutes(options.inet6RouteRange).forEach { builder.addRoute(it.address(), it.prefix()) }
            }
        }

        val include = collectStrings(options.includePackage)
        val exclude = collectStrings(options.excludePackage)
        include.forEach { runCatching { builder.addAllowedApplication(it) } }
        exclude.filter { it != packageName }.forEach { runCatching { builder.addDisallowedApplication(it) } }

        systemProxyAvailable = options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= 29
        if (systemProxyAvailable && systemProxyEnabled) {
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(
                options.httpProxyServer,
                options.httpProxyServerPort,
                collectStrings(options.httpProxyBypassDomain),
            ))
        }
        builder.setConfigureIntent(openAppIntent())
        val pfd = builder.establish() ?: error("安卓拒绝建立 Box VPN 接口")
        tun = pfd
        return pfd.fd
    }

    private fun collectRoutes(iterator: RoutePrefixIterator?): List<RoutePrefix> {
        if (iterator == null) return emptyList()
        val out = ArrayList<RoutePrefix>()
        while (iterator.hasNext()) out += iterator.next()
        return out
    }

    private fun collectStrings(iterator: StringIterator?): List<String> {
        if (iterator == null) return emptyList()
        val out = ArrayList<String>()
        while (iterator.hasNext()) out += iterator.next()
        return out
    }

    private fun notification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, BoxVpnService::class.java).setAction(STOP), flags)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_service_active)
            .setContentTitle("绘 · Box")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "断开", stop)
            .setOnlyAlertOnce(true).setOngoing(true).build()
    }

    private fun openAppIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), flags)
    }

    private fun startHeartbeat() {
        heartbeat?.interrupt()
        heartbeat = Thread({
            while (wanted && !Thread.currentThread().isInterrupted) {
                try { Thread.sleep(4_000L) } catch (_: InterruptedException) { break }
                if (wanted) writeStatus(
                    "RUNNING", "Box 已连接 · $version",
                    lastTxRate, lastRxRate, lastTxTotal, lastRxTotal,
                )
            }
        }, "hui-box-heartbeat").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun writeStatus(state: String, message: String, txRate: Long = 0L, rxRate: Long = 0L, txTotal: Long = 0L, rxTotal: Long = 0L) {
        runCatching {
            val dir = home().apply { mkdirs() }
            val json = JSONObject()
                .put("state", state).put("message", message)
                .put("txRate", txRate).put("rxRate", rxRate)
                .put("txTotal", txTotal).put("rxTotal", rxTotal)
                .put("version", version).put("updatedAt", System.currentTimeMillis())
            val pending = File(dir, "status.pending")
            FileOutputStream(pending).use { out -> out.write(json.toString().toByteArray()); out.fd.sync() }
            val target = statusFile()
            if (target.exists()) target.delete()
            if (!pending.renameTo(target)) pending.copyTo(target, overwrite = true).also { pending.delete() }
        }
    }

    private fun readable(t: Throwable): String {
        val raw = generateSequence(t) { it.cause }.mapNotNull { it.message?.trim() }.firstOrNull { it.isNotEmpty() }
            ?: t.javaClass.simpleName
        return raw.take(240)
    }

    private fun stopCurrent(preserveError: Boolean) {
        if (!stopping.compareAndSet(false, true)) return
        wanted = false
        runCatching { commandClient?.disconnect() }
        commandClient = null
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { tun?.close() }
        tun = null
        worker = null
        heartbeat?.interrupt()
        heartbeat = null
        if (!preserveError) writeStatus("STOPPED", "未连接")
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        val restartProcess = CoreNativeOverride.consumeRestartRequired(this, "box")
        stopSelf()
        if (restartProcess) Thread {
            try { Thread.sleep(120L) } catch (_: InterruptedException) { }
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }

    override fun serviceStop() {
        wanted = false
        Thread { stopCurrent(false) }.start()
    }

    override fun serviceReload() {
        try {
            val config = configFile().readText()
            Libbox.checkConfig(config)
            requireNotNull(commandServer) { "Box 控制服务尚未就绪" }
                .startOrReloadService(config, createOverrides())
        } catch (t: Throwable) {
            Log.e("HuiBox", "reload failure", t)
            writeStatus("ERROR", "Box 重载失败：${readable(t)}")
            wanted = false
            Thread { stopCurrent(true) }.start()
        }
    }
    override fun getSystemProxyStatus() = SystemProxyStatus().apply { available = systemProxyAvailable; enabled = systemProxyEnabled }
    override fun setSystemProxyEnabled(enabled: Boolean) { systemProxyEnabled = enabled; serviceReload() }
    override fun triggerNativeCrash() = throw UnsupportedOperationException("native crash trigger disabled")
    override fun connectSSHAgent() = -1
    override fun writeDebugMessage(message: String?) { Log.d("HuiBox", message.orEmpty()) }

    override fun connected() = Unit
    override fun disconnected(message: String?) {
        if (!wanted) return
        val reason = message?.takeIf { it.isNotBlank() } ?: "Box 控制连接已断开"
        writeStatus("ERROR", reason.take(240))
        wanted = false
        Thread { stopCurrent(true) }.start()
    }
    override fun clearLogs() = Unit
    override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit
    override fun setDefaultLogLevel(level: Int) = Unit
    override fun updateClashMode(newMode: String?) = Unit
    override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
    override fun writeGroups(message: OutboundGroupIterator?) = Unit
    override fun writeLogs(messageList: LogIterator?) = Unit
    override fun writeOutbounds(message: OutboundGroupItemIterator?) = Unit
    override fun writeStatus(status: StatusMessage?) {
        status ?: return
        lastTxRate = status.uplink
        lastRxRate = status.downlink
        lastTxTotal = status.uplinkTotal
        lastRxTotal = status.downlinkTotal
        writeStatus(
            "RUNNING", "Box 已连接 · $version",
            lastTxRate, lastRxRate, lastTxTotal, lastRxTotal,
        )
    }

    override fun onRevoke() { stopCurrent(false); super.onRevoke() }
    override fun onDestroy() { stopCurrent(false); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
}
