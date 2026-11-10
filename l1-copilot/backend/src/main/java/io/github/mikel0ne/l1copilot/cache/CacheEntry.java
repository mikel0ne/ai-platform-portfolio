package io.github.mikel0ne.l1copilot.cache;

import java.time.Instant;

record CacheEntry(String value, Instant expiresAt) {

    boolean isExpired() {
        return !Instant.now().isBefore(expiresAt);
    }
}
