package io.github.mikel0ne.l1copilot.cache;

import lombok.Getter;

import java.io.Serializable;

@Getter
public class CacheEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private Object value;
    private long createdAt;
    private long ttlSeconds;

    public CacheEntry(Object value, long ttlSeconds) {
        this.value = value;
        this.createdAt = System.currentTimeMillis();
        this.ttlSeconds = ttlSeconds;
    }

    public boolean isExpired() {
        long elapsedMillis = System.currentTimeMillis() - createdAt;
        return elapsedMillis >= ttlSeconds * 1000L;
    }
}
