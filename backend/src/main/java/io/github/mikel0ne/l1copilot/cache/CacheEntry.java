package io.github.mikel0ne.l1copilot.cache;

import lombok.Getter;

import java.io.Serializable;

/**
 * Entrada de caché con marca temporal y TTL en segundos.
 * <p>
 * La comparación se hace en milisegundos para evitar truncar a segundos enteros
 * (que dejaba "vivir" una entrada con TTL=1s casi dos segundos).
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

    /** {@code true} si han pasado al menos {@code ttlSeconds} desde la creación. */
    public boolean isExpired() {
        long elapsedMillis = System.currentTimeMillis() - createdAt;
        return elapsedMillis >= ttlSeconds * 1000L;
    }
}
