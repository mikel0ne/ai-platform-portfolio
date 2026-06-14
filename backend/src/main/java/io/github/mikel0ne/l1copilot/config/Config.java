package io.github.mikel0ne.l1copilot.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Configuración de LangChain4j para Google AI Studio (Gemini) en Spring Boot.
 */
@Configuration
@EnableCaching
public class Config {

    private static final Logger log = LoggerFactory.getLogger(Config.class);

    @Bean
    public ChatModel chatModel(LangChain4jProperties properties) {
        if (!StringUtils.hasText(properties.apiKey())) {
            log.warn("GEMINI_API_KEY no configurada: las llamadas al modelo fallarán. "
                    + "Obtén una en https://aistudio.google.com/app/apikey");
        }
        log.info("Inicializando Google AI Studio Gemini ChatModel (modelo: {}, timeout: {}s, reintentos: {})",
                properties.modelName(), properties.timeout().toSeconds(), properties.maxRetries());

        return GoogleAiGeminiChatModel.builder()
                .apiKey(properties.apiKey())
                .modelName(properties.modelName())
                .timeout(properties.timeout())
                .maxRetries(properties.maxRetries())
                .build();
    }

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager("llm-cache");
    }
}
