package io.github.mikel0ne.l1copilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades de la caché de respuestas LLM con persistencia en fichero.
 */
@ConfigurationProperties(prefix = "cache")
public record CacheProperties(File file, Long ttlSeconds) {

    public record File(String path) {
        public File {
            if (path == null || path.isBlank()) {
                path = "./cache";
            }
        }
    }

    public CacheProperties {
        if (file == null) {
            file = new File("./cache");
        }
        if (ttlSeconds == null) {
            ttlSeconds = 3600L;
        }
    }
}
