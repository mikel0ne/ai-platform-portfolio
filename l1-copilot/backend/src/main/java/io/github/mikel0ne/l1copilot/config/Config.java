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

@Configuration
public class Config {

    private static final Logger log = LoggerFactory.getLogger(Config.class);

    private static final String GOOGLE_OPENAI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/openai/";

    @Bean
    @Primary
    @Deterministic
    public ChatModel chatModel(LangChain4jProperties properties, CacheProperties cacheProperties, FileCacheManager cache) {
        return buildModel("deterministic", properties.temperatures().deterministic(),
                properties.thinkingLevels().deterministic(), properties, cacheProperties, cache);
    }

    @Bean
    @TicketModel
    public ChatModel ticketChatModel(LangChain4jProperties properties, CacheProperties cacheProperties, FileCacheManager cache) {
        return buildModel("ticket", properties.temperatures().ticket(),
                properties.thinkingLevels().ticket(), properties, cacheProperties, cache);
    }

    @Bean
    @ResponseModel
    public ChatModel responseChatModel(LangChain4jProperties properties, CacheProperties cacheProperties, FileCacheManager cache) {
        return buildModel("response", properties.temperatures().response(),
                properties.thinkingLevels().response(), properties, cacheProperties, cache);
    }

    @Bean
    @ChattyModel
    public ChatModel chatChatModel(LangChain4jProperties properties, CacheProperties cacheProperties, FileCacheManager cache) {
        return buildModel("chat", properties.temperatures().chat(),
                properties.thinkingLevels().chat(), properties, cacheProperties, cache);
    }

    private ChatModel buildModel(String role, double temperature, String thinkingLevel,
                                 LangChain4jProperties properties,
                                 CacheProperties cacheProperties, FileCacheManager cache) {
        if (!StringUtils.hasText(properties.apiKey())) {
            log.warn("GEMINI_API_KEY is not set: model calls will fail. "
                    + "Get one at https://aistudio.google.com/app/apikey");
        }

        String modelName = properties.modelName();
        ChatModel delegate;

        if (modelName.startsWith("gemma")) {
            log.info("Initialising Gemma [{}] via the OpenAI-compatible Google AI Studio endpoint "
                    + "(model: {}, temperature: {}, timeout: {}s, retries: {})",
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
            log.info("Initialising Google AI Studio Gemini [{}] "
                    + "(model: {}, temperature: {}, thinking: {}, timeout: {}s, retries: {})",
                    role, modelName, temperature,
                    level != null ? level : "NONE",
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
            log.info("LLM cache ENABLED for [{}] (ttl={}s, file={})",
                    role, cacheProperties.ttlSeconds(), cacheProperties.file().enabled());
            return new CachingChatModel(delegate, cache);
        } else {
            log.info("LLM cache DISABLED for [{}] (cache.enabled=false): every request goes to the model", role);
            return delegate;
        }
    }

    private GeminiThinkingLevel parseThinkingLevel(String level) {
        if (!StringUtils.hasText(level) || "NONE".equalsIgnoreCase(level)) return null;
        try {
            return GeminiThinkingLevel.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown thinking level '{}'; thinking disabled for this role", level);
            return null;
        }
    }
}
