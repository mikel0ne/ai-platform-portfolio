package io.github.mikel0ne.autoops.rag;

import io.weaviate.client.Config;
import io.weaviate.client.WeaviateClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cliente de Weaviate. Construir el cliente <b>no abre conexión</b> (solo guarda host/puerto): la primera
 * llamada real ocurre en la ingesta o en una búsqueda, por lo que el arranque del servidor MCP no depende
 * de que Weaviate esté disponible (igual criterio que los beans LLM {@code @Lazy} del doc 06).
 */
@Configuration
public class WeaviateConfig {

    @Bean
    public WeaviateClient weaviateClient(WeaviateProperties props) {
        String host = props.host() + ":" + props.port();
        return new WeaviateClient(new Config(props.scheme(), host));
    }
}
