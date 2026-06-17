package io.github.mikel0ne.l1copilot.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.github.mikel0ne.l1copilot.cache.CachingChatModel;
import io.github.mikel0ne.l1copilot.cache.FileCacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Configuración de LangChain4j para Google AI Studio en Spring Boot.
 * <p>
 * Soporta dos familias de modelos, ambas autenticadas con la misma {@code GEMINI_API_KEY}:
 * <ul>
 *   <li><b>gemini-*</b> → {@link GoogleAiGeminiChatModel} (API nativa de Gemini)</li>
 *   <li><b>gemma-*</b>  → {@link OpenAiChatModel} apuntando al endpoint compatible
 *       OpenAI de Google AI Studio ({@code generativelanguage.googleapis.com/v1beta/openai/}).
 *       Gemma tiene cuotas gratuitas mucho más generosas que Gemini.</li>
 * </ul>
 * El {@code ChatModel} expuesto se envuelve por {@link CachingChatModel} solo si
 * {@code cache.enabled=true}; de lo contrario el delegado se usa directamente.
 */
@Configuration
public class Config {

    private static final Logger log = LoggerFactory.getLogger(Config.class);

    /** Endpoint de Google AI Studio compatible con el protocolo OpenAI. */
    private static final String GOOGLE_OPENAI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/openai/";

    @Bean
    public ChatModel chatModel(LangChain4jProperties properties, CacheProperties cacheProperties, FileCacheManager cache) {
        if (!StringUtils.hasText(properties.apiKey())) {
            log.warn("GEMINI_API_KEY no configurada: las llamadas al modelo fallarán. "
                    + "Obtén una en https://aistudio.google.com/app/apikey");
        }

        String modelName = properties.modelName();
        ChatModel delegate;

        if (modelName.startsWith("gemma")) {
            log.info("Inicializando Gemma via endpoint OpenAI-compatible de Google AI Studio "
                    + "(modelo: {}, timeout: {}s, reintentos: {})",
                    modelName, properties.timeout().toSeconds(), properties.maxRetries());
            delegate = OpenAiChatModel.builder()
                    .apiKey(properties.apiKey())
                    .baseUrl(GOOGLE_OPENAI_BASE_URL)
                    .modelName(modelName)
                    .temperature(properties.temperature())
                    .timeout(properties.timeout())
                    .maxRetries(properties.maxRetries())
                    .build();
        } else {
            log.info("Inicializando Google AI Studio Gemini ChatModel "
                    + "(modelo: {}, timeout: {}s, reintentos: {})",
                    modelName, properties.timeout().toSeconds(), properties.maxRetries());
            delegate = GoogleAiGeminiChatModel.builder()
                    .apiKey(properties.apiKey())
                    .modelName(modelName)
                    .temperature(properties.temperature())
                    .timeout(properties.timeout())
                    .maxRetries(properties.maxRetries())
                    .build();
        }

        if (cacheProperties.isCacheEnabled()) {
            log.info("Caché LLM ACTIVADA (ttl={}s, fichero={})",
                    cacheProperties.ttlSeconds(), cacheProperties.file().enabled());
            return new CachingChatModel(delegate, cache);
        } else {
            log.info("Caché LLM DESACTIVADA (cache.enabled=false): las peticiones van siempre al modelo");
            return delegate;
        }
    }
}
