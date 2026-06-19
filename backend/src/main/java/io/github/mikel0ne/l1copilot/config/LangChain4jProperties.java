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
        Temperatures temperatures) {

    /**
     * Temperaturas por rol del LLM. La creatividad se reparte según la tarea:
     * <ul>
     *   <li>{@code deterministic} (router + verificador): clasifican y juzgan, devuelven
     *       JSON. Queremos la MISMA salida para la misma entrada (0.0, sin creatividad).</li>
     *   <li>{@code ticket}: reescribe la incidencia del cliente. Necesita algo de fluidez
     *       para limpiar erratas/tacos, pero sin inventar datos → muy baja (0.2).</li>
     *   <li>{@code response}: redacta la respuesta de las gestiones de negocio. Un punto
     *       más natural y menos plana, sin desviarse de los datos (0.35).</li>
     *   <li>{@code chat}: charla informal (modo "chatty"). Aquí sí buscamos calidez y
     *       variedad (0.7).</li>
     * </ul>
     */
    public record Temperatures(Double deterministic, Double ticket, Double response, Double chat) {
        public Temperatures {
            if (deterministic == null) {
                deterministic = 0.0;
            }
            if (ticket == null) {
                ticket = 0.2;
            }
            if (response == null) {
                response = 0.35;
            }
            if (chat == null) {
                chat = 0.7;
            }
        }
    }

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
        if (temperatures == null) {
            temperatures = new Temperatures(null, null, null, null);
        }
    }
}
