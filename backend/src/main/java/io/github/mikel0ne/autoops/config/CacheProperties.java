package io.github.mikel0ne.autoops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades de la caché de respuestas LLM.
 * <p>
 * {@code cache.enabled=false} desactiva completamente la caché (memoria + disco):
 * el {@code ChatModel} delegado se usa directamente sin ningún wrapper.
 * <p>
 * La caché vive siempre en memoria. La persistencia en fichero es opcional
 * ({@code cache.file.enabled}) y se desactiva en entornos efímeros como
 * Kubernetes, donde un volumen extra solo para esto no aporta nada.
 */
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

    /** Devuelve {@code true} si la caché está activa globalmente. */
    public boolean isCacheEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
