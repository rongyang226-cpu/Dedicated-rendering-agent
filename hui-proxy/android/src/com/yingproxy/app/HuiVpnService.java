package com.yingproxy.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import io.github.oviron.libmihomo.Clash;
import io.github.oviron.libmihomo.InvokeInterface;
import io.github.oviron.libmihomo.TunInterface;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** The foreground VPN owns the core and TUN. The WebView never claims a connection itself. */
public final class HuiVpnService extends VpnService {
    static final String START = "com.yingproxy.app.CONNECT";
    static final String STOP = "com.yingproxy.app.DISCONNECT";
    private static final String CHANNEL = "hui_vpn";
    private static final int NOTIFICATION = 32;
    private static volatile boolean running;
    private final AtomicBoolean wanted = new AtomicBoolean();
    private Thread worker;
    private ParcelFileDescriptor tun;
    private SharedPreferences prefs;
    private final TunInterface coreNetwork = new TunInterface() {
        @Override public void protect(int fd) {
            if (!HuiVpnService.this.protect(fd)) Log.w("HuiVpn", "保护代理套接字失败");
        }
        @Override public String resolverProcess(int protocol, String source, String target, int uid) { return ""; }
    };

    static boolean isRunning() { return running; }

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("hui_local_settings", MODE_PRIVATE);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "绘 · VPN", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            wanted.set(false);
            if (worker == null || !worker.isAlive()) stopCurrent();
            return START_NOT_STICKY;
        }
        if (!START.equals(intent.getAction())) return START_NOT_STICKY;
        if (worker != null && worker.isAlive()) return START_NOT_STICKY;
        startForeground(NOTIFICATION, notification("正在检查代理配置"));
        wanted.set(true);
        status("STARTING", "正在检查配置");
        worker = new Thread(this::connect, "hui-vpn-core");
        worker.start();
        return START_NOT_STICKY;
    }

    private Notification notification(String message) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent off = PendingIntent.getService(this, 1, new Intent(this, HuiVpnService.class).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(com.yingbao.app.R.drawable.hui_icon)
            .setContentTitle("绘 · VPN").setContentText(message).setContentIntent(content)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "断开", off)
            .setOngoing(true).build();
    }

    private void status(String state, String message) {
        prefs.edit().putString("vpn_status", state).putString("vpn_message", message).apply();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && ("STARTING".equals(state) || "RUNNING".equals(state)))
            manager.notify(NOTIFICATION, notification(message));
    }

    private void connect() {
        try {
            if (VpnService.prepare(this) != null) throw new IllegalStateException("VPN 授权已失效，请重新授权");
            String id = prefs.getString("active", "");
            if (!id.matches("[0-9]+\\.(yaml|yml)")) throw new IllegalArgumentException("请先选中 Mihomo YAML 配置");
            File input = new File(new File(getFilesDir(), "profiles"), id);
            if (!input.isFile() || input.length() == 0 || input.length() > 1024 * 1024)
                throw new IllegalArgumentException("配置不存在或超过 1 MB");
            File home = new File(getFilesDir(), "mihomo");
            if (!home.isDirectory() && !home.mkdirs()) throw new IllegalStateException("无法创建内核目录");
            File config = new File(home, "config.yaml");
            String yaml;
            try (FileInputStream in = new FileInputStream(input)) {
                byte[] bytes = new byte[(int) input.length()];
                int off = 0, n;
                while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) != -1) off += n;
                if (off != bytes.length) throw new IllegalStateException("配置读取不完整");
                yaml = new String(bytes, StandardCharsets.UTF_8);
            }
            if (!yaml.contains("proxies:") && !yaml.contains("proxy-providers:"))
                throw new IllegalArgumentException("配置缺少节点或代理提供者");
            yaml = ConfigOverrides.apply(yaml, prefs.getString("mode", "RULE"),
                prefs.getString("dns", "IMPORTED"),
                !"IMPORTED".equals(prefs.getString("dns", "IMPORTED")) && prefs.getBoolean("dns_follow_rules", false));
            Clash core = Clash.INSTANCE;
            core.load(getApplicationInfo().nativeLibraryDir);
            if (core.bridgeABI() != Clash.EXPECTED_BRIDGE_ABI)
                throw new IllegalStateException("Mihomo 核心与桥接版本不匹配");
            File pending = new File(home, "config.pending");
            try (FileOutputStream output = new FileOutputStream(pending)) {
                output.write(yaml.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            JSONObject action = new JSONObject().put("id", "preflight").put("method", "validateConfig")
                .put("data", pending.getAbsolutePath());
            String validation = awaitAction(core, action.toString());
            JSONObject result = new JSONObject(validation);
            if (result.optInt("code", -1) != 0 || !result.optString("data", "").isEmpty())
                throw new IllegalArgumentException("内核校验失败：" + result.optString("data", "未知错误"));
            if (!pending.renameTo(config)) throw new IllegalStateException("无法保存有效配置");
            if (!wanted.get()) return;
            String init = new JSONObject().put("home-dir", home.getAbsolutePath())
                .put("version", Build.VERSION.SDK_INT).toString();
            String setup = new JSONObject().put("selected-map", new JSONObject()).toString();
            String setupError = awaitSetup(core, init, setup);
            if (!setupError.isEmpty()) throw new IllegalArgumentException("内核启动失败：" + setupError);
            if (!wanted.get()) return;
            // With no IPv6 address, route or DNS, Android blocks IPv6 instead of leaking it outside VPN.
            Builder builder = new Builder().setSession("绘").setMtu(1400)
                .addAddress("172.19.0.1", 30).addRoute("0.0.0.0", 0)
                .addDnsServer("172.19.0.2");
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
            tun = builder.establish();
            if (tun == null) throw new IllegalStateException("安卓未能建立 VPN 接口");
            String dnsHijack = prefs.getBoolean("dns_strict", true) ? "any" : "172.19.0.2";
            core.startTUN(tun.getFd(), coreNetwork, "hui", "system", "172.19.0.1/30", dnsHijack, 1400);
            if (!wanted.get()) return;
            // startTUN may report failure only inside the native log; the UI remains explicit about this limit.
            running = true;
            status("RUNNING", "VPN 已建立 · 请核对出口与 DNS");
        } catch (Exception failure) {
            String reason = failure.getMessage();
            if (reason == null || reason.length() > 150) reason = failure.getClass().getSimpleName();
            Log.e("HuiVpn", failure.getClass().getSimpleName());
            status("ERROR", reason);
            wanted.set(false);
        } finally {
            if (!wanted.get()) stopCurrent();
        }
    }

    private String awaitAction(Clash core, String json) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        core.invokeAction(json, new InvokeInterface() {
            @Override public void onResult(String value) { result.set(value); ready.countDown(); }
        });
        if (!ready.await(25, TimeUnit.SECONDS)) throw new IllegalStateException("核心配置检查超时");
        if (result.get() == null) throw new IllegalStateException("核心没有返回校验结果");
        return result.get();
    }

    private String awaitSetup(Clash core, String init, String setup) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        core.quickSetup(init, setup, new InvokeInterface() {
            @Override public void onResult(String value) { result.set(value); ready.countDown(); }
        });
        if (!ready.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("内核启动超时");
        return result.get() == null ? "内核未确认启动" : result.get();
    }

    private synchronized void stopCurrent() {
        running = false;
        try { if (Clash.INSTANCE.isLoaded()) Clash.INSTANCE.stopTun(); }
        catch (Exception e) { Log.e("HuiVpn", "内核停止失败", e); }
        try { if (tun != null) tun.close(); }
        catch (Exception e) { Log.e("HuiVpn", "VPN 关闭失败", e); }
        tun = null;
        if (!"ERROR".equals(prefs.getString("vpn_status", ""))) status("STOPPED", "未连接");
        stopForeground(true);
        stopSelf();
    }
    @Override public void onRevoke() { wanted.set(false); stopCurrent(); super.onRevoke(); }
    @Override public void onDestroy() { wanted.set(false); stopCurrent(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return super.onBind(intent); }
}
