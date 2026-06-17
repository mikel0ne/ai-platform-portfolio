package io.github.mikel0ne.l1copilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Propiedades de configuración de LangChain4j para Google AI Studio (Gemini).
 * <p>
 * La API key NUNCA debe estar en el repositorio: se inyecta por la variable de
 * entorno {@code GEMINI_API_KEY} (en Kubernetes, vía Secret {@code gemini-api-key}).
 */
@ConfigurationProperties(prefix = "langchain4j.gemini")
public record LangChain4jProperties(
        String apiKey,
        String modelName,
        Duration timeout,
        Integer maxRetries,
        Double temperature) {

    public LangChain4jProperties {
        if (modelName == null || modelName.isBlank()) {
            modelName = "gemini-2.5-flash-lite";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(30);
        }
        if (maxRetries == null) {
            maxRetries = 2;
        }
        // Temperatura 0 por defecto: el agente clasifica y decide acciones; queremos
        // la MISMA salida para la misma entrada (consistencia), no creatividad.
        if (temperature == null) {
            temperature = 0.0;
        }
    }
}
