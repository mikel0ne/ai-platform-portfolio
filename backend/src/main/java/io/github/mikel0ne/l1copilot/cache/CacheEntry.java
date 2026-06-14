package io.github.mikel0ne.l1copilot.cache;

import lombok.Getter;

import java.io.Serializable;

/**
 * Entrada de caché con timestamp y TTL
 */
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

    /**
     * Verifica si la entrada ha expirado
     */
    public boolean isExpired() {
        long currentTime = System.currentTimeMillis();
        long elapsedSeconds = (currentTime - createdAt) / 1000;
        return elapsedSeconds > ttlSeconds;
    }
}

