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
import android.graphics.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int IMPORT_REQUEST = 115;
    private static final int MAX_CONFIG_BYTES = 1024 * 1024;
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

    private String state() {
        JSONObject out = new JSONObject();
        JSONArray items = new JSONArray();
        try {
            out.put("engine", prefs.getString("engine", "SING_BOX"));
            out.put("dns", prefs.getString("dns", "BUILT_IN"));
            out.put("rules", prefs.getString("rules", "BUILT_IN"));
            out.put("mode", prefs.getString("mode", "RULE"));
            out.put("udp", prefs.getString("udp", "PROXY"));
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
            out.put("ready", false);
            out.put("status", "尚未接入 VPN 与代理内核");
        } catch (Exception e) {
            return "{\"engine\":\"SING_BOX\",\"dns\":\"BUILT_IN\",\"rules\":\"BUILT_IN\",\"mode\":\"RULE\",\"udp\":\"PROXY\",\"active\":\"\",\"profiles\":[],\"ready\":false,\"status\":\"无法读取本机配置\"}";
        }
        return out.toString();
    }

    public final class Bridge {
        @JavascriptInterface public String getState() { return state(); }
        @JavascriptInterface public void setOption(String key, String value) {
            boolean ok = false;
            if ("engine".equals(key)) ok = "SING_BOX".equals(value) || "MIHOMO".equals(value) || "XRAY".equals(value);
            if ("dns".equals(key) || "rules".equals(key)) ok = "BUILT_IN".equals(value) || "IMPORTED".equals(value);
            if ("mode".equals(key)) ok = "RULE".equals(value) || "GLOBAL".equals(value) || "DIRECT".equals(value);
            if ("udp".equals(key)) ok = "PROXY".equals(value) || "BLOCK".equals(value);
            if (ok) prefs.edit().putString(key, value).apply();
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
        @JavascriptInterface public void activate(String id) {
            if (id != null && id.matches("[0-9]+\\.(yaml|yml|json)") && new File(profileDir(), id).isFile())
                prefs.edit().putString("active", id).apply();
            update();
        }
        @JavascriptInterface public void delete(String id) {
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
            toast("已保存为草稿；尚不能用于连接");
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
