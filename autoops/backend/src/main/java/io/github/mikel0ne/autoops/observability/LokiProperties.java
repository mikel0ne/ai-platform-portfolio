package io.github.mikel0ne.autoops.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Conexión a Loki (logs agregados, doc 05 §1, roadmap F3/F5). En local, vía port-forward
 * ({@code kubectl port-forward svc/loki 3100:3100 -n observability}); en K8s, por el Service compartido
 * del namespace {@code observability} (ver {@code application-k8s.yml}).
 *
 * @param scheme {@code http} | {@code https}
 * @param host   host de Loki
 * @param port   puerto HTTP
 */
@Validated
@ConfigurationProperties("autoops.loki")
public record LokiProperties(String scheme, String host, Integer port) {
    public LokiProperties {
        if (scheme == null || scheme.isBlank()) scheme = "http";
        if (host == null || host.isBlank()) host = "localhost";
        if (port == null) port = 3100;
    }

    public String baseUrl() {
        return scheme + "://" + host + ":" + port;
    }
}
