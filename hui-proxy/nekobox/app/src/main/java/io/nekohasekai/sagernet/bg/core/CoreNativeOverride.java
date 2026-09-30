package io.nekohasekai.sagernet.bg.core;

import android.content.Context;
import android.util.Log;
import java.io.File;
import org.json.JSONObject;

/** Loads verified, app-private native core updates with bundled-library fallback. */
public final class CoreNativeOverride {
    private static final String TAG = "HuiCoreOverride";
    private CoreNativeOverride() {}

    public static void configure(Context context, String process) {
        if (process == null) return;
        if (process.endsWith(":box")) configureEngine(context, "box", new String[]{"libbox.so"});
        if (process.endsWith(":meta")) configureEngine(context, "meta", new String[]{"libclash.so", "libbridge.so"});
    }

    private static void configureEngine(Context context, String engine, String[] libraries) {
        try {
            File dir = new File(context.getFilesDir(), "core-updates/" + engine);
            File active = new File(dir, "active.json");
            if (!active.isFile()) return;
            JSONObject json = new JSONObject(readText(active));
            String version = json.optString("version", "");
            File versionDir = new File(dir, version);
            if (version.isEmpty() || !versionDir.isDirectory()) return;
            for (String library : libraries) {
                File file = new File(versionDir, library);
                if (!file.isFile() || file.length() < 4096L) return;
                System.setProperty("hui.core." + engine + "." + library, file.getAbsolutePath());
            }
            System.setProperty("hui.core." + engine + ".manifest", active.getAbsolutePath());
            System.setProperty("hui.core." + engine + ".version", version);
        } catch (Throwable e) {
            Log.w(TAG, "Unable to configure " + engine + " override", e);
        }
    }

    public static boolean loadBox() {
        return loadOne("box", "libbox.so", "box");
    }

    public static boolean loadMeta() {
        String clash = System.getProperty("hui.core.meta.libclash.so", "");
        String bridge = System.getProperty("hui.core.meta.libbridge.so", "");
        if (clash.isEmpty() || bridge.isEmpty()) return false;
        try {
            System.load(clash);
            System.load(bridge);
            Log.i(TAG, "Loaded Meta override " + System.getProperty("hui.core.meta.version", ""));
            return true;
        } catch (Throwable error) {
            rollback("meta", error);
            return false;
        }
    }

    private static boolean loadOne(String engine, String key, String bundled) {
        String path = System.getProperty("hui.core." + engine + "." + key, "");
        if (path.isEmpty()) return false;
        try {
            System.load(path);
            Log.i(TAG, "Loaded " + engine + " override " + System.getProperty("hui.core." + engine + ".version", ""));
            return true;
        } catch (Throwable error) {
            rollback(engine, error);
            return false;
        }
    }

    public static boolean consumeRestartRequired(Context context, String engine) {
        try {
            File marker = new File(context.getFilesDir(), "core-updates/" + engine + "/restart-required");
            return marker.isFile() && marker.delete();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void rollback(String engine, Throwable error) {
        Log.e(TAG, "Updated " + engine + " core failed to load; falling back to bundled core", error);
        try {
            String path = System.getProperty("hui.core." + engine + ".manifest", "");
            if (!path.isEmpty()) {
                File active = new File(path);
                File failed = new File(active.getParentFile(), "failed-" + System.currentTimeMillis() + ".json");
                // A failed update must not be retried on every process launch.
                if (!active.renameTo(failed)) active.delete();
                new File(active.getParentFile(), "restart-required").delete();
            }
        } catch (Throwable ignored) { }
    }

    private static String readText(File file) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
