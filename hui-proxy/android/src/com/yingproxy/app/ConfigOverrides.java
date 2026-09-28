package com.yingproxy.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies the few supported UI overrides to a copy of a Mihomo profile. */
final class ConfigOverrides {
    private static final Pattern MODE = Pattern.compile("(?m)^mode\\s*:[^\\r\\n]*(?:\\r?\\n|$)");
    private static final Pattern DNS = Pattern.compile("(?m)^dns\\s*:[^\\r\\n]*(?:\\r?\\n|$)");
    private static final Pattern TUN = Pattern.compile("(?m)^tun\\s*:[^\\r\\n]*(?:\\r?\\n|$)");
    private static final Pattern ROOT_KEY = Pattern.compile("(?m)^[a-zA-Z][a-zA-Z0-9_-]*\\s*:");
    private static final Pattern DNS_ENABLED = Pattern.compile("(?m)^\\s+enable\\s*:\\s*(true|yes|on)\\s*(?:#.*)?$");

    private ConfigOverrides() {}

    static String apply(String source, String mode, String dnsChoice) {
        if (!"RULE".equals(mode) && !"GLOBAL".equals(mode) && !"DIRECT".equals(mode))
            throw new IllegalArgumentException("无效的路由模式");
        if (!"IMPORTED".equals(dnsChoice) && !"ALI_DOH".equals(dnsChoice) && !"CF_DOH".equals(dnsChoice))
            throw new IllegalArgumentException("无效的 DNS 来源");
        String yaml = source.startsWith("\ufeff") ? source.substring(1) : source;
        Matcher modeMatch = MODE.matcher(yaml);
        int modes = 0;
        while (modeMatch.find()) modes++;
        if (modes > 1) throw new IllegalArgumentException("配置包含重复的顶层 mode");
        yaml = modeMatch.replaceAll("");
        // The app supplies the Android VPN fd itself. A profile's desktop TUN must not start a second listener.
        Matcher tun = TUN.matcher(yaml);
        if (tun.find()) {
            int start = tun.start();
            Matcher nextTunKey = ROOT_KEY.matcher(yaml);
            int end = nextTunKey.find(tun.end()) ? nextTunKey.start() : yaml.length();
            if (TUN.matcher(yaml.substring(end)).find()) throw new IllegalArgumentException("配置包含重复的顶层 tun");
            yaml = yaml.substring(0, start) + yaml.substring(end);
        }

        Matcher dns = DNS.matcher(yaml);
        if (!dns.find()) throw new IllegalArgumentException("配置缺少顶层 DNS 设置");
        int start = dns.start();
        int bodyStart = dns.end();
        Matcher next = ROOT_KEY.matcher(yaml);
        int end = next.find(bodyStart) ? next.start() : yaml.length();
        if (DNS.matcher(yaml.substring(end)).find()) throw new IllegalArgumentException("配置包含重复的顶层 DNS");
        if ("IMPORTED".equals(dnsChoice)) {
            if (!DNS_ENABLED.matcher(yaml.substring(bodyStart, end)).find())
                throw new IllegalArgumentException("导入配置必须开启 dns.enable 才能接管 VPN DNS");
        } else {
            String endpoint = "ALI_DOH".equals(dnsChoice) ? "https://223.5.5.5/dns-query" : "https://1.1.1.1/dns-query";
            String replacement = "dns:\n  enable: true\n  ipv6: false\n  enhanced-mode: fake-ip\n"
                + "  fake-ip-range: 198.18.0.1/16\n  nameserver:\n    - " + endpoint + "\n"
                + "  proxy-server-nameserver:\n    - " + endpoint + "\n";
            yaml = yaml.substring(0, start) + replacement + yaml.substring(end);
        }
        return "mode: " + mode.toLowerCase(Locale.ROOT) + "\ntun:\n  enable: false\n" + yaml;
    }
}
