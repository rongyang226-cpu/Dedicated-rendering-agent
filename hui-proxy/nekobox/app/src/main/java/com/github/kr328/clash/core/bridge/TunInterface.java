package com.github.kr328.clash.core.bridge;
public interface TunInterface {
    void markSocket(int fd);
    int querySocketUid(int protocol, String source, String target);
}
