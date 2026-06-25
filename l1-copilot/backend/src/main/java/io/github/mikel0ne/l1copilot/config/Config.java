package io.github.mikel0ne.l1copilot.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import dev.langchain4j.model.googleai.GeminiThinkingConfig.GeminiThinkingLevel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.github.mikel0ne.l1copilot.cache.CachingChatModel;
import io.github.mikel0ne.l1copilot.cache.FileCacheManager;
import io.github.mikel0ne.l1copilot.config.qualifier.ChattyModel;
import io.github.mikel0ne.l1copilot.config.qualifier.Deterministic;
import io.github.mikel0ne.l1copilot.config.qualifier.ResponseModel;
import io.github.mikel0ne.l1copilot.config.qualifier.TicketModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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
 * Cada bean {@code ChatModel} se envuelve por {@link CachingChatModel} solo si
 * {@code cache.enabled=true}; de lo contrario el delegado se usa directamente.
 * <p>
 * Se exponen CUATRO modelos, uno por nivel de creatividad (ver
 * {@link LangChain4jProperties.Temperatures}). Comparten modelo, timeout y
 * reintentos; solo cambia la temperatura:
 * <ul>
 *   <li>{@code chatModel} (primario, determinista 0.0) → router + verificador</li>
 *   <li>{@code ticketChatModel} (0.2) → redacción de tickets</li>
 *   <li>{@code responseChatModel} (0.35) → respuestas de negocio</li>
 *   <li>{@code chatChatModel} (0.7) → charla informal (modo chatty)</li>
 * </ul>
 */
@Configuration
public class Config {

    private static final Logger log = LoggerFactory.getLogger(Config.class);

    /** Endpoint de Google AI Studio compatible con el protocolo OpenAI. */
    private static final String GOOGLE_OPENAI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/openai/";

    /**
     * Modelo determinista (temperatura 0.0). Es el {@link Primary}: lo usan el router,
     * el verificador y cualquier inyección de {@code ChatModel} sin cualificar.
     */
    @Bean
    @Primary
    @Deterministic
    public ChatModel chatModel(LangChain4jProperties properties, CacheProperties cacheProperties, FileCacheManager cache) {
        return buildModel("determinista", properties.temperatures().deterministic(),
                properties.thinkingLevels().deterministic(), properties, cacheProperties, cache);
    }

    /** Modelo para redacción de tickets (temperatura baja: fluidez sin inventar datos). */
    @Bean
    @TicketModel
    public ChatModel ticketChatModel(LangChain4jProperties properties, CacheProperties cacheProperties, FileCacheManager cache) {
        return buildModel("tickets", properties.temperatures().ticket(),
                properties.thinkingLevels().ticket(), properties, cacheProperties, cache);
    }

    /** Modelo para respuestas de negocio (temperatura media: tono más natural). */
    @Bean
    @ResponseModel
    public ChatModel responseChatModel(LangChain4jProperties properties, CacheProperties cacheProperties, FileCacheManager cache) {
        return buildModel("respuesta", properties.temperatures().response(),
                properties.thinkingLevels().response(), properties, cacheProperties, cache);
    }

    /** Modelo para charla informal (temperatura alta: calidez y variedad). */
    @Bean
    @ChattyModel
    public ChatModel chatChatModel(LangChain4jProperties properties, CacheProperties cacheProperties, FileCacheManager cache) {
        return buildModel("chat", properties.temperatures().chat(),
                properties.thinkingLevels().chat(), properties, cacheProperties, cache);
    }

    /**
     * Construye un {@link ChatModel} con la temperatura y el nivel de thinking indicados.
     * Si la caché está activa, lo envuelve en {@link CachingChatModel}.
     * El thinking level solo aplica a modelos Gemini nativos ({@code gemini-*}); para
     * Gemma ({@code gemma-*}, vía endpoint OpenAI-compatible) se ignora silenciosamente.
     */
    private ChatModel buildModel(String role, double temperature, String thinkingLevel,
                                 LangChain4jProperties properties,
                                 CacheProperties cacheProperties, FileCacheManager cache) {
        if (!StringUtils.hasText(properties.apiKey())) {
            log.warn("GEMINI_API_KEY no configurada: las llamadas al modelo fallarán. "
                    + "Obtén una en https://aistudio.google.com/app/apikey");
        }

        String modelName = properties.modelName();
        ChatModel delegate;

        if (modelName.startsWith("gemma")) {
            log.info("Inicializando Gemma [{}] via endpoint OpenAI-compatible de Google AI Studio "
                    + "(modelo: {}, temperatura: {}, timeout: {}s, reintentos: {})",
                    role, modelName, temperature, properties.timeout().toSeconds(), properties.maxRetries());
            delegate = OpenAiChatModel.builder()
                    .apiKey(properties.apiKey())
                    .baseUrl(GOOGLE_OPENAI_BASE_URL)
                    .modelName(modelName)
                    .temperature(temperature)
                    .timeout(properties.timeout())
                    .maxRetries(properties.maxRetries())
                    .build();
        } else {
            GeminiThinkingLevel level = parseThinkingLevel(thinkingLevel);
            log.info("Inicializando Google AI Studio Gemini ChatModel [{}] "
                    + "(modelo: {}, temperatura: {}, thinking: {}, timeout: {}s, reintentos: {})",
                    role, modelName, temperature,
                    level != null ? level : "NONE (desactivado)",
                    properties.timeout().toSeconds(), properties.maxRetries());
            var builder = GoogleAiGeminiChatModel.builder()
                    .apiKey(properties.apiKey())
                    .modelName(modelName)
                    .temperature(temperature)
                    .timeout(properties.timeout())
                    .maxRetries(properties.maxRetries());
            if (level != null) {
                builder.thinkingConfig(GeminiThinkingConfig.builder().thinkingLevel(level).build());
            }
            delegate = builder.build();
        }

        if (cacheProperties.isCacheEnabled()) {
            log.info("Caché LLM ACTIVADA para [{}] (ttl={}s, fichero={})",
                    role, cacheProperties.ttlSeconds(), cacheProperties.file().enabled());
            return new CachingChatModel(delegate, cache);
        } else {
            log.info("Caché LLM DESACTIVADA para [{}] (cache.enabled=false): las peticiones van siempre al modelo", role);
            return delegate;
        }
    }

    /**
     * Convierte el string de configuración en el enum de la API de Gemini.
     * "NONE" o valor vacío → {@code null} (no se aplica thinking config).
     * Valores no reconocidos se tratan como {@code null} para no romper el arranque.
     */
    private GeminiThinkingLevel parseThinkingLevel(String level) {
        if (!StringUtils.hasText(level) || "NONE".equalsIgnoreCase(level)) return null;
        try {
            return GeminiThinkingLevel.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Nivel de thinking '{}' no reconocido; se desactiva el thinking para este rol", level);
            return null;
        }
    }
}
