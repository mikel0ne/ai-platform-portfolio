package io.github.mikel0ne.autoops.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("autoops.weaviate")
public record WeaviateProperties(
        String scheme,
        String host,
        Integer port,
        String className
) {
    public WeaviateProperties {
        if (scheme == null || scheme.isBlank()) scheme = "http";
        if (host == null || host.isBlank()) host = "localhost";
        if (port == null) port = 8088;
        if (className == null || className.isBlank()) className = "DocChunk";
    }
}
