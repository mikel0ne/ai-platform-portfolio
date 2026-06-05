package io.github.mikel0ne.l1copilot.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuración de LangChain4j para Google AI Studio (Gemini) en Spring Boot
 */
@Configuration
@EnableCaching
public class Config {

    private static final Logger log = LoggerFactory.getLogger(Config.class);

    @Value("${langchain4j.gemini.api-key}")
    private String apiKey;

    @Value("${langchain4j.gemini.model-name:gemini-1.5-flash}")
    private String modelName;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        log.info("Inicializando Google AI Studio Gemini ChatModel");
        log.info("  Modelo: {}", modelName);

        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
    }

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager("llm-cache");
    }
}
