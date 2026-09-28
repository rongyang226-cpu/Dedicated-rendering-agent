package com.yingproxy.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Engine-independent settings. A VPN must not be advertised as protected until every check passes. */
public final class ProxyPolicy {
    public enum Engine { SING_BOX, MIHOMO, XRAY }
    public enum Source { BUILT_IN, IMPORTED }
    public enum Mode { RULE, GLOBAL, DIRECT }
    public enum Udp { PROXY, BLOCK, DIRECT }
    public final Engine engine;
    public final Source dnsSource;
    public final Source rulesSource;
    public final Mode mode;
    public final Udp udp;
    public final boolean captureIpv4Dns;
    public final boolean captureIpv6Dns;
    public final boolean tunnelIpv6;
    public final boolean blockOnDisconnect;
    public final boolean allowAppBypass;
    public final String remoteDns;
    public final String domesticDns;
    public final List<String> bypassApps;

    public ProxyPolicy(Engine engine, Source dnsSource, Source rulesSource, Mode mode,
                       Udp udp, boolean captureIpv4Dns, boolean captureIpv6Dns,
                       boolean tunnelIpv6, boolean blockOnDisconnect, boolean allowAppBypass,
                       String remoteDns, String domesticDns, List<String> bypassApps) {
        this.engine = engine; this.dnsSource = dnsSource; this.rulesSource = rulesSource;
        this.mode = mode; this.udp = udp; this.captureIpv4Dns = captureIpv4Dns;
        this.captureIpv6Dns = captureIpv6Dns; this.tunnelIpv6 = tunnelIpv6;
        this.blockOnDisconnect = blockOnDisconnect; this.allowAppBypass = allowAppBypass;
        this.remoteDns = remoteDns; this.domesticDns = domesticDns;
        this.bypassApps = Collections.unmodifiableList(new ArrayList<>(bypassApps));
    }

    /** Blocking errors; no optimistic fallback to system DNS or DIRECT on invalid configuration. */
    public List<String> validate(boolean androidLockdownEnabled, boolean importedDnsPresent,
                                 boolean importedRulesPresent, boolean engineSupportsIpv6) {
        List<String> errors = new ArrayList<>();
        if (engine == null || dnsSource == null || rulesSource == null || mode == null || udp == null)
            errors.add("核心、DNS、规则、模式和 UDP 策略必须明确设置");
        if (!captureIpv4Dns) errors.add("必须接管 IPv4 DNS");
        if (!captureIpv6Dns && tunnelIpv6) errors.add("IPv6 已启用时必须接管 IPv6 DNS");
        if (tunnelIpv6 && !engineSupportsIpv6) errors.add("当前内核或 TUN 配置不支持 IPv6");
        if (blockOnDisconnect && !androidLockdownEnabled)
            errors.add("断线阻断需在安卓系统中开启始终开启 VPN 与阻止无 VPN 连接");
        if (allowAppBypass && blockOnDisconnect)
            errors.add("断线阻断模式不能允许应用绕过 VPN");
        if (!allowAppBypass && !bypassApps.isEmpty())
            errors.add("禁止应用绕过时，排除应用列表必须为空");
        if (dnsSource == Source.IMPORTED && !importedDnsPresent)
            errors.add("导入配置缺少 DNS，不能静默回退为系统 DNS");
        if (rulesSource == Source.IMPORTED && !importedRulesPresent)
            errors.add("导入配置缺少分流规则");
        if (dnsSource == Source.BUILT_IN && (blank(remoteDns) || blank(domesticDns)))
            errors.add("内置 DNS 必须同时配置远端和国内解析器");
        if (udp == Udp.DIRECT && mode != Mode.DIRECT)
            errors.add("代理模式下直连全部 UDP 会造成流量绕过");
        return errors;
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
