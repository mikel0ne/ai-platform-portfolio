package io.github.mikel0ne.autoops.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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
