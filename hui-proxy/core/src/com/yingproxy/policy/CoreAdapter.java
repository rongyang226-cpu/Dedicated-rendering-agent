package com.yingproxy.policy;

/** An engine owns its config translation, process lifecycle, DNS and packet handling. */
public interface CoreAdapter {
    ProxyPolicy.Engine engine();
    void validateConfig(ProxyPolicy policy, String source) throws Exception;
    void start(int tunFd, ProxyPolicy policy, String source, FdProtector protector) throws Exception;
    void stop() throws Exception;
    boolean isRunning();

    interface FdProtector {
        boolean protect(int socketFd);
    }
}
