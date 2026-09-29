package com.github.kr328.clash.core.bridge;
public interface FetchCallback {
    void report(String statusJson);
    void complete(String error);
}
