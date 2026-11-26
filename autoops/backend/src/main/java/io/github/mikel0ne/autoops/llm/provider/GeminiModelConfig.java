package io.github.mikel0ne.autoops.llm.provider;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import io.github.mikel0ne.autoops.config.CacheProperties;
import io.github.mikel0ne.autoops.config.LlmProperties;
import io.github.mikel0ne.autoops.observability.langfuse.LangfuseProperties;
import io.github.mikel0ne.platform.commons.cache.CachingChatModel;
import io.github.mikel0ne.platform.commons.cache.FileCacheManager;
import io.github.mikel0ne.platform.commons.langfuse.LangfuseChatModelListener;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;

import java.util.List;

@Configuration
@ConditionalOnProperty(name = "autoops.llm.provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiModelConfig {

    @Bean
    @Lazy
    @Primary
    public EmbeddingModel embeddingModel(LlmProperties props,
                                         @Value("${GEMINI_API_KEY:}") String apiKey) {
        return GoogleAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(props.embeddingModelName())
                .taskType(GoogleAiEmbeddingModel.TaskType.RETRIEVAL_DOCUMENT)
                .timeout(props.timeout())
                .maxRetries(props.maxRetries())
                .build();
    }

    @Bean("queryEmbeddingModel")
    @Lazy
    public EmbeddingModel queryEmbeddingModel(LlmProperties props,
                                              @Value("${GEMINI_API_KEY:}") String apiKey) {
        return GoogleAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(props.embeddingModelName())
                .taskType(GoogleAiEmbeddingModel.TaskType.RETRIEVAL_QUERY)
                .timeout(props.timeout())
                .maxRetries(props.maxRetries())
                .build();
    }

    @Bean
    @Lazy
    public ChatModel chatModel(LlmProperties props, CacheProperties cacheProps, FileCacheManager cache,
                               Tracer langfuseTracer, LangfuseProperties langfuseProps,
                               @Value("${GEMINI_API_KEY:}") String apiKey) {
        ChatModel model = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(props.chatModelName())
                .temperature(props.temperature())
                .timeout(props.timeout())
                .maxRetries(props.maxRetries())
                .listeners(List.of(new LangfuseChatModelListener(langfuseTracer, "internal", langfuseProps.isCaptureContent())))
                .build();
        return cacheProps.isCacheEnabled() ? new CachingChatModel(model, cache) : model;
    }
}
