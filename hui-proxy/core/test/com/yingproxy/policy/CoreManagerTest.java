package com.yingproxy.policy;

import java.util.Collections;

public final class CoreManagerTest {
    private static final class Core implements CoreAdapter {
        private final ProxyPolicy.Engine engine;
        boolean running;
        boolean reject;
        boolean startWithoutRunning;
        int starts;
        int stops;
        Core(ProxyPolicy.Engine engine) { this.engine = engine; }
        @Override public ProxyPolicy.Engine engine() { return engine; }
        @Override public void validateConfig(ProxyPolicy policy, String source) {
            if (reject) throw new IllegalArgumentException("invalid config");
        }
        @Override public void start(int fd, ProxyPolicy policy, String source, FdProtector protector) {
            starts++;
            running = !startWithoutRunning;
        }
        @Override public void stop() { stops++; running = false; }
        @Override public boolean isRunning() { return running; }
    }

    private static ProxyPolicy policy(ProxyPolicy.Engine engine) {
        return new ProxyPolicy(engine, ProxyPolicy.Source.IMPORTED,
            ProxyPolicy.Source.IMPORTED, ProxyPolicy.Mode.RULE, ProxyPolicy.Udp.PROXY,
            true, true, false, false, false, null, null, Collections.emptyList());
    }
    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }
    private static boolean fails(RunnableWithException action) {
        try { action.run(); return false; } catch (Exception expected) { return true; }
    }
    private interface RunnableWithException { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        CoreManager manager = new CoreManager();
        Core sing = new Core(ProxyPolicy.Engine.SING_BOX);
        Core meta = new Core(ProxyPolicy.Engine.MIHOMO);
        manager.register(sing);
        manager.register(meta);
        check(fails(() -> manager.start(2, policy(ProxyPolicy.Engine.XRAY), "{}", fd -> true)));
        check(manager.state() == CoreManager.State.STOPPED);
        sing.reject = true;
        check(fails(() -> manager.start(2, policy(sing.engine()), "{}", fd -> true)));
        check(manager.state() == CoreManager.State.ERROR && sing.starts == 0);
        manager.stop();
        sing.reject = false;
        sing.startWithoutRunning = true;
        check(fails(() -> manager.start(2, policy(sing.engine()), "{}", fd -> true)));
        check(manager.state() == CoreManager.State.ERROR && sing.stops == 3);
        manager.stop();
        sing.startWithoutRunning = false;
        manager.start(2, policy(sing.engine()), "{}", fd -> true);
        check(manager.state() == CoreManager.State.RUNNING && manager.activeEngine() == sing.engine());
        check(fails(() -> manager.start(3, policy(meta.engine()), "{}", fd -> true)));
        check(meta.starts == 0);
        manager.stop();
        manager.start(3, policy(meta.engine()), "{}", fd -> true);
        check(manager.state() == CoreManager.State.RUNNING && meta.starts == 1);
        manager.stop();
        check(manager.state() == CoreManager.State.STOPPED);
        System.out.println("CoreManager lifecycle: 7 cases OK");
    }
}
