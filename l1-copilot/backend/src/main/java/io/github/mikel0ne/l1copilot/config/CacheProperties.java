package io.github.mikel0ne.l1copilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cache")
public record CacheProperties(Boolean enabled, File file, Long ttlSeconds) {
    public record File(Boolean enabled, String path) {
        public File {
            if (enabled == null) {
                enabled = true;
            }
            if (path == null || path.isBlank()) {
                path = "./cache";
            }
        }
    }

    public CacheProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (file == null) {
            file = new File(true, "./cache");
        }
        if (ttlSeconds == null) {
            ttlSeconds = 3600L;
        }
    }

    public boolean isCacheEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
