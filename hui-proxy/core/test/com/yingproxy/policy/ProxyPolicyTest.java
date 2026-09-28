package com.yingproxy.policy;

import java.util.Collections;
import java.util.List;

public final class ProxyPolicyTest {
    private static ProxyPolicy policy(ProxyPolicy.Source dns, boolean dnsCapture,
                                      boolean lockdown, ProxyPolicy.Udp udp) {
        return new ProxyPolicy(ProxyPolicy.Engine.SING_BOX, dns, ProxyPolicy.Source.BUILT_IN,
            ProxyPolicy.Mode.RULE, udp, dnsCapture, true, false, lockdown, false,
            "https://dns.google/dns-query", "223.5.5.5", Collections.emptyList());
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(policy(ProxyPolicy.Source.BUILT_IN, true, false, ProxyPolicy.Udp.PROXY)
            .validate(false, false, true, true).isEmpty(), "valid policy rejected");
        check(!policy(ProxyPolicy.Source.IMPORTED, true, false, ProxyPolicy.Udp.PROXY)
            .validate(false, false, true, true).isEmpty(), "missing imported DNS accepted");
        check(!policy(ProxyPolicy.Source.BUILT_IN, false, false, ProxyPolicy.Udp.PROXY)
            .validate(false, false, true, true).isEmpty(), "DNS bypass accepted");
        check(!policy(ProxyPolicy.Source.BUILT_IN, true, true, ProxyPolicy.Udp.PROXY)
            .validate(false, false, true, true).isEmpty(), "lockdown falsely advertised");
        check(!policy(ProxyPolicy.Source.BUILT_IN, true, false, ProxyPolicy.Udp.DIRECT)
            .validate(false, false, true, true).isEmpty(), "UDP bypass accepted");
        System.out.println("ProxyPolicy: 5 cases OK");
    }
}
