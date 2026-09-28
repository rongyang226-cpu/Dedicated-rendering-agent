package com.yingproxy.policy;

import java.util.EnumMap;
import java.util.Map;

/** Owns the one allowed mobile proxy core. No state is reported as connected until the core confirms it. */
public final class CoreManager {
    public enum State { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

    private final Map<ProxyPolicy.Engine, CoreAdapter> adapters =
        new EnumMap<>(ProxyPolicy.Engine.class);
    private CoreAdapter active;
    private State state = State.STOPPED;
    private String error = "";

    public synchronized void register(CoreAdapter adapter) {
        if (state != State.STOPPED || adapter == null || adapter.engine() == null)
            throw new IllegalStateException("只能在停止状态注册有效内核");
        if (adapters.putIfAbsent(adapter.engine(), adapter) != null)
            throw new IllegalArgumentException("重复注册代理核心");
    }

    public synchronized State state() { return state; }
    public synchronized String error() { return error; }
    public synchronized ProxyPolicy.Engine activeEngine() {
        return active == null ? null : active.engine();
    }

    /** Call before establishing Android VPN so invalid configuration cannot open a TUN. */
    public synchronized void validate(ProxyPolicy policy, String config) throws Exception {
        if (state != State.STOPPED) throw new IllegalStateException("只能在停止状态检查配置");
        if (policy == null || config == null) throw new IllegalArgumentException("配置不能为空");
        CoreAdapter adapter = adapters.get(policy.engine);
        if (adapter == null) throw new IllegalStateException("所选代理核心未安装");
        adapter.validateConfig(policy, config);
    }

    public synchronized void start(int tunFd, ProxyPolicy policy, String config,
                                   CoreAdapter.FdProtector protector) throws Exception {
        if (state != State.STOPPED) throw new IllegalStateException("当前代理核心尚未停止");
        if (tunFd < 0 || policy == null || config == null || protector == null)
            throw new IllegalArgumentException("TUN、配置和套接字保护不能为空");
        CoreAdapter adapter = adapters.get(policy.engine);
        if (adapter == null) throw new IllegalStateException("所选代理核心未安装");
        if (adapter.isRunning()) throw new IllegalStateException("代理核心已在运行");
        state = State.STARTING;
        error = "";
        active = adapter;
        try {
            adapter.validateConfig(policy, config);
            adapter.start(tunFd, policy, config, protector);
            if (!adapter.isRunning()) throw new IllegalStateException("代理核心没有确认启动");
            state = State.RUNNING;
        } catch (Exception failure) {
            try {
                adapter.stop();
            } catch (Exception stopFailure) {
                failure.addSuppressed(stopFailure);
            }
            error = failure.getClass().getSimpleName();
            state = State.ERROR;
            throw failure;
        }
    }

    public synchronized void stop() throws Exception {
        if (active == null) {
            state = State.STOPPED;
            error = "";
            return;
        }
        state = State.STOPPING;
        try {
            active.stop();
            if (active.isRunning()) throw new IllegalStateException("代理核心仍在运行");
            active = null;
            state = State.STOPPED;
            error = "";
        } catch (Exception failure) {
            state = State.ERROR;
            error = failure.getClass().getSimpleName();
            throw failure;
        }
    }
}
