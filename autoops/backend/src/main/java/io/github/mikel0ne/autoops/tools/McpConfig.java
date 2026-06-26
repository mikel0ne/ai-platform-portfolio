package io.github.mikel0ne.autoops.tools;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registro de las tools MCP. Spring AI MCP Server toma este {@link ToolCallbackProvider}, genera el
 * esquema de cada método {@code @Tool} y lo publica por el transporte configurado (SSE en K8s, stdio
 * en local). El cliente MCP descubre las tools por introspección.
 *
 * <p>A medida que se implementen las fases del roadmap se añaden aquí los beans de tools
 * (RAG en F1, Kubernetes en F2, logs en F3).
 */
@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider autoopsTools(DiagnosticsTools diagnostics,
                                             DocsTools docs,
                                             KubernetesTools kubernetes,
                                             ObservabilityTools observability) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(diagnostics, docs, kubernetes, observability)
                .build();
    }
}
