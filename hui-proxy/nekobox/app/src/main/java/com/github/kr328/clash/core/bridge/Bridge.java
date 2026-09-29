package com.github.kr328.clash.core.bridge;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import java.io.File;
import io.nekohasekai.sagernet.BuildConfig;
import io.nekohasekai.sagernet.SagerNet;
import kotlinx.coroutines.CompletableDeferred;
import kotlin.Unit;

/** JNI surface kept source-compatible with CMFA v2.11.34's native bridge. */
public final class Bridge {
    public static final Bridge INSTANCE = new Bridge();
    static {
        System.loadLibrary("clash");
        System.loadLibrary("bridge");
    }
    private Bridge() {
        try {
            ParcelFileDescriptor.open(new File(SagerNet.application.getPackageCodePath()), ParcelFileDescriptor.MODE_READ_ONLY).detachFd();
        } catch (Throwable ignored) { }
        File home = new File(SagerNet.application.getFilesDir(), "meta");
        if (!home.isDirectory()) home.mkdirs();
        nativeInit(home.getAbsolutePath(), BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT);
    }
    public native void nativeReset();
    public native void nativeForceGc();
    public native void nativeSuspend(boolean suspend);
    public native String nativeQueryTunnelState();
    public native long nativeQueryTrafficNow();
    public native long nativeQueryTrafficTotal();
    public native void nativeNotifyDnsChanged(String dnsList);
    public native void nativeNotifyTimeZoneChanged(String name, int offset);
    public native void nativeNotifyInstalledAppChanged(String uidList);
    public native void nativeStartTun(int fd, String stack, String gateway, String portal, String dns, TunInterface cb);
    public native void nativeStopTun();
    public native String nativeStartHttp(String listenAt);
    public native void nativeStopHttp();
    public native String nativeQueryGroupNames(boolean excludeNotSelectable);
    public native String nativeQueryGroup(String name, String sort);
    public native void nativeHealthCheck(CompletableDeferred<Unit> completable, String name);
    public native void nativeHealthCheckAll();
    public native boolean nativePatchSelector(String selector, String name);
    public native void nativeFetchAndValid(FetchCallback callback, String path, String url, boolean force);
    public native void nativeLoad(CompletableDeferred<Unit> completable, String path);
    public native String nativeQueryProviders();
    public native void nativeUpdateProvider(CompletableDeferred<Unit> completable, String type, String name);
    public native String nativeReadOverride(int slot);
    public native void nativeWriteOverride(int slot, String content);
    public native void nativeClearOverride(int slot);
    public native String nativeQueryConfiguration();
    public native void nativeSubscribeLogcat(LogcatInterface callback);
    public native String nativeCoreVersion();
    public native void nativeSetAgeSecretKey(String key);
    public native String nativeGenX25519KeyPair();
    public native String nativeGenHybridKeyPair();
    public native boolean nativeVeritySecretKeys(String secretKeys);
    public native String nativeToPublicKeys(String secretKeys);
    public native boolean nativeVerityPublicKeys(String publicKeys);
    private native void nativeInit(String home, String versionName, int sdkVersion);
}
