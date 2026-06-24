package io.github.mikel0ne.autoops.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuración del proveedor LLM/embeddings, abstraído (ADR-003 / doc 06). El código no conoce el
 * proveedor concreto: lo elige {@code autoops.llm.provider} (por defecto {@code gemini}).
 *
 * <p>El LLM es de uso <b>interno</b> (calificar/reformular en el RAG correctivo y redactar el chat);
 * el razonamiento de cara al usuario lo pone el cliente MCP.
 */
@Validated
@ConfigurationProperties("autoops.llm")
public record LlmProperties(
        @NotBlank String provider,            // gemini | (futuros: azure-openai, ollama...)
        @NotBlank String chatModelName,
        @NotBlank String embeddingModelName,
        Duration timeout,
        Double temperature
) {
    public LlmProperties {
        if (timeout == null) timeout = Duration.ofSeconds(30);
        if (temperature == null) temperature = 0.2;
    }
}
