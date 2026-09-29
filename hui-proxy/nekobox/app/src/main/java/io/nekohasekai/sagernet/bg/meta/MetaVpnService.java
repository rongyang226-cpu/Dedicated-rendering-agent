package io.nekohasekai.sagernet.bg.meta;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import com.github.kr328.clash.core.bridge.Bridge;
import com.github.kr328.clash.core.bridge.TunInterface;
import io.nekohasekai.sagernet.R;
import io.nekohasekai.sagernet.ui.MainActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/** CMFA 2.11.34 / Mihomo 1.19.31 isolated VPN process. */
public final class MetaVpnService extends VpnService {
    public static final String START = "com.yingbao.hui.META_START";
    public static final String STOP = "com.yingbao.hui.META_STOP";
    private static final String CHANNEL = "hui-meta-vpn";
    private static final int NOTIFICATION = 47;
    private volatile boolean wanted;
    private volatile boolean running;
    private volatile boolean preserveErrorOnDestroy;
    private Thread worker;
    private Thread trafficWorker;
    private ParcelFileDescriptor tun;
    private int mtu = 9000;
    private boolean proxyApps;
    private boolean bypassApps = true;
    private boolean bypassLan;
    private boolean allowIpv6;
    private volatile String coreVersion = "Mihomo";
    private ArrayList<String> individualApps = new ArrayList<>();
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver packageReceiver;
    private BroadcastReceiver timeZoneReceiver;
    private final ConcurrentHashMap<Network, List<String>> networkDns = new ConcurrentHashMap<>();

    private final TunInterface network = new TunInterface() {
        @Override public void markSocket(int fd) { MetaVpnService.this.protect(fd); }
        @Override public int querySocketUid(int protocol, String source, String target) {
            return MetaVpnService.this.querySocketUid(protocol, source, target);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "绘 · Meta", NotificationManager.IMPORTANCE_LOW));
        }
        preserveErrorOnDestroy = false;
        writeStatus("STOPPED", "未连接", 0, 0, 0, 0);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? STOP : intent.getAction();
        if (STOP.equals(action)) {
            wanted = false;
            stopCurrent(false);
            return START_NOT_STICKY;
        }
        if (!START.equals(action)) return START_NOT_STICKY;
        if (worker != null && worker.isAlive()) return START_NOT_STICKY;
        mtu = Math.max(1280, Math.min(9000, intent.getIntExtra("mtu", 9000)));
        proxyApps = intent.getBooleanExtra("proxyApps", false);
        bypassApps = intent.getBooleanExtra("bypassApps", true);
        bypassLan = intent.getBooleanExtra("bypassLan", false);
        allowIpv6 = intent.getBooleanExtra("allowIpv6", false);
        ArrayList<String> list = intent.getStringArrayListExtra("individualApps");
        individualApps = list == null ? new ArrayList<>() : list;
        startForeground(NOTIFICATION, notification("正在启动 Meta"));
        wanted = true;
        writeStatus("STARTING", "正在加载 Meta 配置", 0, 0, 0, 0);
        worker = new Thread(this::connect, "hui-meta-core");
        worker.start();
        return START_NOT_STICKY;
    }

    private Notification notification(String message) {
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            immutable | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent off = PendingIntent.getService(this, 1,
            new Intent(this, MetaVpnService.class).setAction(STOP),
            immutable | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_service_active)
            .setContentTitle("绘 · Meta")
            .setContentText(message)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "断开", off)
            .setOnlyAlertOnce(true).setOngoing(true).build();
    }

    private File home() { return new File(getFilesDir(), "meta"); }
    private File profile() { return new File(home(), "profiles/active"); }
    private File config() { return new File(profile(), "config.yaml"); }
    private File status() { return new File(home(), "status.json"); }

    private void connect() {
        boolean preserveError = false;
        try {
            if (VpnService.prepare(this) != null) throw new IllegalStateException("VPN 授权已失效，请重新授权");
            if (!config().isFile() || config().length() <= 0) throw new IllegalArgumentException("请先导入 Meta YAML 配置");
            Bridge core = Bridge.INSTANCE;
            core.nativeReset();
            notifyInstalledApps(core);
            notifyTimeZone(core);
            registerRuntimeObservers(core);
            MetaNative.loadProfile(profile().getAbsolutePath());
            if (!wanted) return;

            Builder builder = new Builder().setSession("绘 · Meta").setMtu(mtu)
                .setBlocking(false)
                .addAddress("172.19.0.1", 30)
                .addDnsServer("172.19.0.2");
            applyVpnRoutes(builder);
            if (allowIpv6) {
                builder.addAddress("fdfe:dcba:9876::1", 126)
                    .addDnsServer("fdfe:dcba:9876::2");
            }
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
            applyPerAppRules(builder);
            tun = builder.establish();
            if (tun == null) throw new IllegalStateException("安卓拒绝建立 Meta VPN 接口");
            String gateway = allowIpv6 ? "172.19.0.1/30,fdfe:dcba:9876::1/126" : "172.19.0.1/30";
            String portal = allowIpv6 ? "172.19.0.2,fdfe:dcba:9876::2" : "172.19.0.2";
            core.nativeStartTun(tun.getFd(), "system", gateway, portal, "0.0.0.0", network);
            if (!wanted) return;
            running = true;
            preserveErrorOnDestroy = false;
            coreVersion = safeVersion();
            writeStatus("RUNNING", "Meta 已连接 · " + coreVersion, 0, 0, 0, 0);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION, notification("Meta 已连接 · " + coreVersion));
            startTraffic(core);
        } catch (Throwable failure) {
            preserveError = true;
            preserveErrorOnDestroy = true;
            String reason = failure.getMessage();
            if (reason == null || reason.length() > 180) reason = failure.getClass().getSimpleName();
            Log.e("HuiMeta", "core failure", failure);
            writeStatus("ERROR", reason, 0, 0, 0, 0);
            wanted = false;
        } finally {
            if (!wanted) stopCurrent(preserveError);
        }
    }


    private void registerRuntimeObservers(Bridge core) {
        unregisterRuntimeObservers(core);
        connectivity = getSystemService(ConnectivityManager.class);
        if (connectivity != null) {
            NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { refreshNetworkDns(core, network, null); }
                @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                    refreshNetworkDns(core, network, properties);
                }
                @Override public void onLost(Network network) {
                    networkDns.remove(network);
                    publishBestDns(core);
                }
            };
            try { connectivity.registerNetworkCallback(request, networkCallback); }
            catch (Throwable e) { Log.w("HuiMeta", "network observer unavailable", e); }
            try {
                for (Network network : connectivity.getAllNetworks()) refreshNetworkDns(core, network, null);
            } catch (Throwable ignored) { }
        }

        packageReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                new Thread(() -> notifyInstalledApps(core), "hui-meta-app-cache").start();
            }
        };
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addDataScheme("package");
        registerCompat(packageReceiver, packages);

        timeZoneReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { notifyTimeZone(core); }
        };
        registerCompat(timeZoneReceiver, new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED));
    }

    private void registerCompat(BroadcastReceiver receiver, IntentFilter filter) {
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else registerReceiver(receiver, filter);
        } catch (Throwable e) {
            Log.w("HuiMeta", "runtime observer registration failed", e);
        }
    }

    private void unregisterRuntimeObservers(Bridge core) {
        if (connectivity != null && networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Throwable ignored) { }
        }
        networkCallback = null;
        networkDns.clear();
        if (packageReceiver != null) try { unregisterReceiver(packageReceiver); } catch (Throwable ignored) { }
        if (timeZoneReceiver != null) try { unregisterReceiver(timeZoneReceiver); } catch (Throwable ignored) { }
        packageReceiver = null;
        timeZoneReceiver = null;
        try { core.nativeNotifyDnsChanged(""); } catch (Throwable ignored) { }
    }

    private void refreshNetworkDns(Bridge core, Network network, LinkProperties supplied) {
        if (connectivity == null || network == null) return;
        try {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                networkDns.remove(network);
                publishBestDns(core);
                return;
            }
            LinkProperties lp = supplied != null ? supplied : connectivity.getLinkProperties(network);
            if (lp == null) return;
            ArrayList<String> dns = new ArrayList<>();
            for (InetAddress address : lp.getDnsServers()) dns.add(formatDns(address));
            networkDns.put(network, dns);
            publishBestDns(core);
        } catch (Throwable e) {
            Log.w("HuiMeta", "DNS observer update failed", e);
        }
    }

    private void publishBestDns(Bridge core) {
        if (connectivity == null) return;
        Network best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Network network : networkDns.keySet()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps == null) continue;
            int score = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 0
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? 1
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? 4 : 20;
            if (score < bestScore) { best = network; bestScore = score; }
        }
        List<String> dns = best == null ? Collections.emptyList() : networkDns.get(best);
        if (dns == null || dns.isEmpty()) return;
        try { core.nativeNotifyDnsChanged(String.join(",", dns)); }
        catch (Throwable e) { Log.w("HuiMeta", "native DNS update failed", e); }
    }

    private static String formatDns(InetAddress address) {
        String host = address.getHostAddress();
        return host != null && host.contains(":") ? "[" + host + "]:53" : host + ":53";
    }

    private void notifyTimeZone(Bridge core) {
        try {
            TimeZone zone = TimeZone.getDefault();
            core.nativeNotifyTimeZoneChanged(zone.getID(), zone.getRawOffset() / 1000);
        } catch (Throwable e) {
            Log.w("HuiMeta", "timezone update failed", e);
        }
    }

    private void applyVpnRoutes(Builder builder) {
        if (bypassLan) {
            for (String cidr : getResources().getStringArray(R.array.bypass_private_route)) {
                int slash = cidr.lastIndexOf('/');
                if (slash <= 0) continue;
                try { builder.addRoute(cidr.substring(0, slash), Integer.parseInt(cidr.substring(slash + 1))); }
                catch (Throwable ignored) { }
            }
            // Meta's synthetic DNS portal must stay inside the VPN even when RFC1918 LANs bypass it.
            builder.addRoute("172.19.0.2", 32);
            if (allowIpv6) {
                builder.addRoute("2000::", 3);
                builder.addRoute("fdfe:dcba:9876::2", 128);
            }
        } else {
            builder.addRoute("0.0.0.0", 0);
            if (allowIpv6) builder.addRoute("::", 0);
        }
    }

    private int querySocketUid(int protocol, String source, String target) {
        if (Build.VERSION.SDK_INT < 29) return -1;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm == null) return -1;
            return cm.getConnectionOwnerUid(protocol, parseSocketAddress(source), parseSocketAddress(target));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static InetSocketAddress parseSocketAddress(String raw) throws Exception {
        URL url = new URL("https://" + raw);
        int port = url.getPort();
        if (port < 0) throw new IllegalArgumentException("missing port");
        return new InetSocketAddress(InetAddress.getByName(url.getHost()), port);
    }

    @SuppressWarnings("deprecation")
    private void notifyInstalledApps(Bridge core) {
        try {
            Map<Integer, String> byUid = new LinkedHashMap<>();
            getPackageManager().getInstalledPackages(0).forEach(info -> {
                if (info.applicationInfo != null) byUid.putIfAbsent(info.applicationInfo.uid, info.packageName);
            });
            StringBuilder list = new StringBuilder();
            for (Map.Entry<Integer, String> entry : byUid.entrySet()) {
                if (list.length() > 0) list.append(',');
                list.append(entry.getKey()).append(':').append(entry.getValue());
            }
            core.nativeNotifyInstalledAppChanged(list.toString());
        } catch (Throwable e) {
            Log.w("HuiMeta", "installed-app cache failed", e);
        }
    }

    private String safeVersion() {
        try { return MetaNative.coreVersion(); } catch (Throwable ignored) { return "Mihomo"; }
    }

    private void applyPerAppRules(Builder builder) {
        if (!proxyApps || individualApps.isEmpty()) return;
        // In include mode keep Hui itself inside the VPN so subscription refresh,
        // connection tests and in-app network tools measure the selected core.
        if (!bypassApps) {
            try { builder.addAllowedApplication(getPackageName()); } catch (Throwable ignored) { }
        }
        for (String pkg : individualApps) {
            if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName())) continue;
            try {
                if (bypassApps) builder.addDisallowedApplication(pkg);
                else builder.addAllowedApplication(pkg);
            } catch (Throwable ignored) { }
        }
    }

    private void startTraffic(Bridge core) {
        trafficWorker = new Thread(() -> {
            while (wanted && running) {
                try {
                    long now = core.nativeQueryTrafficNow();
                    long total = core.nativeQueryTrafficTotal();
                    writeStatus("RUNNING", "Meta 已连接 · " + coreVersion,
                        unpack(now >>> 32), unpack(now & 0xffffffffL),
                        unpack(total >>> 32), unpack(total & 0xffffffffL));
                    Thread.sleep(1000L);
                } catch (InterruptedException e) { return; }
                catch (Throwable e) {
                    Log.w("HuiMeta", "traffic snapshot failed", e);
                    try { Thread.sleep(1500L); } catch (InterruptedException ignored) { return; }
                }
            }
        }, "hui-meta-traffic");
        trafficWorker.start();
    }

    private static long unpack(long value) {
        long type = (value >>> 30) & 0x3L;
        long data = value & 0x3fffffffL;
        if (type == 1) return data * 1024L;
        if (type == 2) return data * 1024L * 1024L;
        if (type == 3) return data * 1024L * 1024L * 1024L;
        return data;
    }

    private synchronized void writeStatus(String state, String message,
        long txRate, long rxRate, long txTotal, long rxTotal) {
        try {
            File dir = home();
            if (!dir.isDirectory()) dir.mkdirs();
            JSONObject json = new JSONObject().put("state", state).put("message", message)
                .put("txRate", txRate).put("rxRate", rxRate)
                .put("txTotal", txTotal).put("rxTotal", rxTotal)
                .put("version", coreVersion)
                .put("updatedAt", System.currentTimeMillis());
            File tmp = new File(dir, "status.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            File target = status();
            if (target.exists()) target.delete();
            if (!tmp.renameTo(target)) {
                try (FileOutputStream out = new FileOutputStream(target)) {
                    out.write(json.toString().getBytes(StandardCharsets.UTF_8));
                }
                tmp.delete();
            }
        } catch (Throwable ignored) { }
    }

    private synchronized void stopCurrent(boolean preserveError) {
        preserveErrorOnDestroy = preserveError;
        wanted = false;
        running = false;
        if (trafficWorker != null) trafficWorker.interrupt();
        trafficWorker = null;
        try { unregisterRuntimeObservers(Bridge.INSTANCE); } catch (Throwable ignored) { }
        try { Bridge.INSTANCE.nativeStopTun(); } catch (Throwable ignored) { }
        try { if (tun != null) tun.close(); } catch (Throwable ignored) { }
        tun = null;
        try { Bridge.INSTANCE.nativeReset(); } catch (Throwable ignored) { }
        if (!preserveError) writeStatus("STOPPED", "未连接", 0, 0, 0, 0);
        try { stopForeground(true); } catch (Throwable ignored) { }
        stopSelf();
    }

    @Override public void onRevoke() { stopCurrent(false); super.onRevoke(); }
    @Override public void onDestroy() { stopCurrent(preserveErrorOnDestroy); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return super.onBind(intent); }
}
