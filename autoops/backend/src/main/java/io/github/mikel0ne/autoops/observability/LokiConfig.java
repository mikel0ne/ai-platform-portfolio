package io.github.mikel0ne.autoops.observability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Cliente HTTP de Loki. Construirlo no abre conexión (mismo criterio que {@code WeaviateConfig}): el
 * arranque del servidor MCP no depende de que Loki esté disponible.
 */
@Configuration
public class LokiConfig {

    @Bean
    public RestClient lokiClient(LokiProperties props) {
        return RestClient.create(props.baseUrl());
    }
}
