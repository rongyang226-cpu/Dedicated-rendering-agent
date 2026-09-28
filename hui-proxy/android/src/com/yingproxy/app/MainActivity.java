package com.yingproxy.app;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebResourceResponse;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.oviron.libmihomo.Clash;

public final class MainActivity extends Activity {
    private static final int IMPORT_REQUEST = 115;
    private static final int BACKGROUND_REQUEST = 116;
    private static final int VPN_PERMISSION_REQUEST = 117;
    private static final int MAX_CONFIG_BYTES = 1024 * 1024;
    private static final int MAX_BACKGROUND_BYTES = 16 * 1024 * 1024;
    private static final String ASSET_URL = "file:///android_asset/index.html";
    private WebView web;
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(32, 28, 37));
        getWindow().setNavigationBarColor(Color.rgb(202, 185, 189));
        getWindow().getDecorView().setBackgroundColor(Color.rgb(202, 185, 189));
        if (android.os.Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        prefs = getSharedPreferences("hui_local_settings", MODE_PRIVATE);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(202, 185, 189));
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !ASSET_URL.equals(request.getUrl().toString());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if ("file".equals(url.getScheme()) && "/android_asset/custom-background.webp".equals(url.getPath())) {
                    try {
                        File image = backgroundFile();
                        if (image.isFile()) return new WebResourceResponse("image/webp", null, new FileInputStream(image));
                    } catch (Exception ignored) { }
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                        java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(view, request);
            }
        });
        web.addJavascriptInterface(new Bridge(), "Hui");
        setContentView(web);
        web.loadUrl(ASSET_URL);
    }

    private void update() {
        runOnUiThread(() -> web.evaluateJavascript("window.refreshFromNative && window.refreshFromNative()", null));
    }

    private File profileDir() {
        File dir = new File(getFilesDir(), "profiles");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File backgroundFile() { return new File(getFilesDir(), "hui-background.webp"); }

    private String state() {
        JSONObject out = new JSONObject();
        JSONArray items = new JSONArray();
        try {
            out.put("engine", "MIHOMO");
            out.put("dns", "IMPORTED");
            out.put("rules", "IMPORTED");
            out.put("mode", "IMPORTED");
            out.put("udp", "IMPORTED");
            out.put("active", prefs.getString("active", ""));
            File[] files = profileDir().listFiles();
            if (files != null) for (File file : files) {
                String id = file.getName();
                if (!id.matches("[0-9]+\\.(yaml|yml|json)")) continue;
                JSONObject obj = new JSONObject();
                obj.put("id", id);
                obj.put("name", prefs.getString("profile_" + id, id));
                obj.put("size", file.length());
                obj.put("check", prefs.getString("profile_check_" + id, "旧配置未检查"));
                items.put(obj);
            }
            out.put("profiles", items);
            out.put("customBackground", backgroundFile().isFile());
            out.put("backgroundVersion", prefs.getLong("background_version", 0));
            android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            out.put("lowMemory", manager != null && manager.isLowRamDevice());
            String selected = prefs.getString("active", "");
            boolean ready = selected.matches("[0-9]+\\.(yaml|yml)")
                && new File(profileDir(), selected).isFile();
            String vpn = prefs.getString("vpn_status", "STOPPED");
            if ("RUNNING".equals(vpn) && !HuiVpnService.isRunning()) vpn = "STOPPED";
            out.put("ready", ready);
            out.put("vpn", vpn);
            out.put("status", "STOPPED".equals(vpn) ? "未连接" : prefs.getString("vpn_message", "未连接"));
            if ("RUNNING".equals(vpn) && Clash.INSTANCE.isLoaded()) {
                try { out.put("traffic", new JSONObject(Clash.INSTANCE.getTraffic())); }
                catch (Exception ignored) { /* Keep unknown values blank rather than show estimates. */ }
            }
        } catch (Exception e) {
            return "{\"engine\":\"MIHOMO\",\"dns\":\"BUILT_IN\",\"rules\":\"BUILT_IN\",\"mode\":\"RULE\",\"udp\":\"PROXY\",\"active\":\"\",\"profiles\":[],\"ready\":false,\"vpn\":\"ERROR\",\"status\":\"无法读取本机配置\"}";
        }
        return out.toString();
    }

    public final class Bridge {
        @JavascriptInterface public String getState() { return state(); }
        @JavascriptInterface public void connect() {
            runOnUiThread(() -> {
                String id = prefs.getString("active", "");
                if (!id.matches("[0-9]+\\.(yaml|yml)") || !new File(profileDir(), id).isFile()) {
                    toast("先导入并选中 Mihomo YAML 配置"); return;
                }
                Intent permission = android.net.VpnService.prepare(MainActivity.this);
                if (permission != null) startActivityForResult(permission, VPN_PERMISSION_REQUEST);
                else startVpn();
            });
        }
        @JavascriptInterface public void disconnect() {
            runOnUiThread(() -> {
                startService(new Intent(MainActivity.this, HuiVpnService.class).setAction(HuiVpnService.STOP));
                update();
            });
        }
        @JavascriptInterface public void setOption(String key, String value) {
            toast("此设置尚未接入内核，当前按导入配置执行");
            update();
        }
        @JavascriptInterface public void importProfile() {
            runOnUiThread(() -> {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.setType("*/*");
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/json", "text/plain", "application/x-yaml", "application/yaml", "application/octet-stream"});
                startActivityForResult(pick, IMPORT_REQUEST);
            });
        }
        @JavascriptInterface public void chooseBackground() {
            runOnUiThread(() -> {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.setType("image/*");
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(pick, BACKGROUND_REQUEST);
            });
        }
        @JavascriptInterface public void resetBackground() {
            new Thread(() -> {
                if (backgroundFile().exists() && !backgroundFile().delete()) {
                    toast("恢复默认背景失败"); return;
                }
                prefs.edit().putLong("background_version", System.currentTimeMillis()).apply();
                update();
            }, "hui-background-reset").start();
        }
        @JavascriptInterface public void activate(String id) {
            if (HuiVpnService.isRunning()) { toast("请先断开 VPN 再切换配置"); return; }
            if (id != null && id.matches("[0-9]+\\.(yaml|yml|json)") && new File(profileDir(), id).isFile())
                prefs.edit().putString("active", id).apply();
            update();
        }
        @JavascriptInterface public void delete(String id) {
            if (HuiVpnService.isRunning()) { toast("请先断开 VPN 再删除配置"); return; }
            if (id != null && id.matches("[0-9]+\\.(yaml|yml|json)")) {
                File file = new File(profileDir(), id);
                if (file.isFile() && file.delete()) {
                    SharedPreferences.Editor edit = prefs.edit().remove("profile_" + id).remove("profile_check_" + id);
                    if (id.equals(prefs.getString("active", ""))) edit.remove("active");
                    edit.apply();
                }
            }
            update();
        }
    }

    private String fileName(Uri uri) {
        String name = "配置";
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) { }
        return name == null ? "配置" : name.trim();
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == VPN_PERMISSION_REQUEST) {
            if (result == RESULT_OK && android.net.VpnService.prepare(this) == null) startVpn();
            else { prefs.edit().putString("vpn_status", "ERROR").putString("vpn_message", "VPN 权限被拒绝").apply(); update(); }
            return;
        }
        if (req == BACKGROUND_REQUEST) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                Uri selected = data.getData();
                new Thread(() -> importBackground(selected), "hui-background-import").start();
            }
            return;
        }
        if (req != IMPORT_REQUEST || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String display = fileName(uri);
        String suffix = display.toLowerCase(java.util.Locale.ROOT).endsWith(".json") ? ".json" :
            display.toLowerCase(java.util.Locale.ROOT).endsWith(".yaml") ? ".yaml" :
            display.toLowerCase(java.util.Locale.ROOT).endsWith(".yml") ? ".yml" : "";
        if (suffix.isEmpty()) { toast("仅支持 JSON 或 YAML 配置"); return; }
        if (display.length() > 64) display = display.substring(0, 64);
        final String safeDisplay = display;
        new Thread(() -> importSelected(uri, safeDisplay, suffix), "hui-profile-import").start();
    }

    private void startVpn() {
        Intent intent = new Intent(this, HuiVpnService.class).setAction(HuiVpnService.START);
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        update();
    }

    private void importBackground(Uri uri) {
        File pending = null;
        Bitmap bitmap = null;
        try {
            byte[] bytes;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) throw new IllegalArgumentException("无法读取图片");
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > MAX_BACKGROUND_BYTES)
                        throw new IllegalArgumentException("图片不能超过 16 MB");
                    out.write(buffer, 0, n);
                }
                bytes = out.toByteArray();
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (opts.outWidth < 200 || opts.outHeight < 200 || opts.outWidth > 8192 || opts.outHeight > 8192)
                throw new IllegalArgumentException("图片尺寸需在 200 至 8192 像素之间");
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = 1;
            while (opts.outWidth / opts.inSampleSize > 2160 || opts.outHeight / opts.inSampleSize > 3840)
                opts.inSampleSize *= 2;
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (bitmap == null) throw new IllegalArgumentException("不支持的图片格式");
            pending = File.createTempFile("hui-background-", ".tmp", getFilesDir());
            try (FileOutputStream output = new FileOutputStream(pending)) {
                if (!bitmap.compress(Bitmap.CompressFormat.WEBP, 85, output))
                    throw new IllegalArgumentException("无法转换图片");
                output.getFD().sync();
            }
            File target = backgroundFile();
            if (!pending.renameTo(target)) throw new IllegalStateException("保存背景失败");
            prefs.edit().putLong("background_version", System.currentTimeMillis()).apply();
            update();
            toast("背景已更新");
        } catch (IllegalArgumentException e) {
            toast("背景导入失败：" + e.getMessage());
        } catch (Exception e) {
            toast("背景导入失败：无法读取或保存图片");
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (pending != null) pending.delete();
        }
    }

    private void importSelected(Uri uri, String display, String suffix) {
        String id = Long.toString(System.currentTimeMillis()) +
            Long.toString(Math.abs(System.nanoTime() % 100000)) + suffix;
        File dst = new File(profileDir(), id);
        File pending = null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalArgumentException("无法读取文件");
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (bytes.size() + n > MAX_CONFIG_BYTES)
                        throw new IllegalArgumentException("配置超过 1 MB");
                    bytes.write(buffer, 0, n);
                }
            }
            byte[] contents = bytes.toByteArray();
            String inspection = ProfileInspector.inspect(contents, suffix);
            pending = File.createTempFile("import-", ".tmp", profileDir());
            try (FileOutputStream out = new FileOutputStream(pending)) {
                out.write(contents);
                out.getFD().sync();
            }
            if (dst.exists() || !pending.renameTo(dst))
                throw new IllegalStateException("保存配置失败");
            prefs.edit().putString("profile_" + id, display)
                .putString("profile_check_" + id, inspection).apply();
            toast("已保存；YAML 会在连接前由内核校验");
        } catch (IllegalArgumentException e) {
            toast("导入失败：" + e.getMessage());
        } catch (Exception e) {
            toast("导入失败：无法读取或保存文件");
        } finally {
            if (pending != null) pending.delete();
            update();
        }
    }

    private void toast(String message) {
        runOnUiThread(() -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show());
    }
    @Override public void onBackPressed() {
        web.evaluateJavascript("window.handleBack && window.handleBack()", value -> {
            if ("\"exit\"".equals(value)) finish();
        });
    }
    @Override protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }
    @Override protected void onDestroy() {
        if (web != null) { web.removeJavascriptInterface("Hui"); web.destroy(); }
        super.onDestroy();
    }
}
