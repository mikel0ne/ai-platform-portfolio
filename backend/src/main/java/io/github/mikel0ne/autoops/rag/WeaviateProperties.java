package io.github.mikel0ne.autoops.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Conexión a Weaviate (vector DB del RAG). En local vía port-forward; en K8s por el Service interno
 * (ver {@code application-k8s.yml}). El esquema de la clase {@code DocChunk} se documenta en doc 05 §4.
 *
 * @param scheme    {@code http} | {@code https}
 * @param host      host de Weaviate
 * @param port      puerto REST/GraphQL
 * @param grpcPort  puerto gRPC (inserciones por lotes)
 * @param className clase/colección de Weaviate que guarda los chunks
 */
@Validated
@ConfigurationProperties("autoops.weaviate")
public record WeaviateProperties(
        String scheme,
        String host,
        Integer port,
        Integer grpcPort,
        String className
) {
    public WeaviateProperties {
        if (scheme == null || scheme.isBlank()) scheme = "http";
        if (host == null || host.isBlank()) host = "localhost";
        if (port == null) port = 8088;
        if (grpcPort == null) grpcPort = 50051;
        if (className == null || className.isBlank()) className = "DocChunk";
    }
}
