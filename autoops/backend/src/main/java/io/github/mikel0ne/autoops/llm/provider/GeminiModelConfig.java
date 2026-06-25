package io.github.mikel0ne.autoops.llm.provider;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import io.github.mikel0ne.autoops.config.LlmProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Implementación por defecto del proveedor: Google AI Studio (Gemini, capa gratuita).
 *
 * <p>Es la ÚNICA clase que importa tipos de un proveedor concreto (regla de oro del doc 06).
 * Para añadir otro proveedor, crear una clase análoga con
 * {@code @ConditionalOnProperty(name = "autoops.llm.provider", havingValue = "<otro>")}.
 *
 * <p>La API key se inyecta por entorno ({@code GEMINI_API_KEY}); si falta, los beans se construyen
 * igualmente y el fallo se produce en la primera llamada, no al arrancar (no bloquea el health).
 */
@Configuration
@ConditionalOnProperty(name = "autoops.llm.provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiModelConfig {

    /**
     * Embeddings del corpus y de las consultas (indexar y buscar en Weaviate).
     * {@code @Lazy}: el cliente del proveedor se construye en el primer uso (RAG, F1), no al arrancar,
     * para que el contexto (servidor MCP + health) levante aunque aún no haya consumidores ni API key.
     */
    @Bean
    @Lazy
    public EmbeddingModel embeddingModel(LlmProperties props,
                                         @Value("${GEMINI_API_KEY:}") String apiKey) {
        return GoogleAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(props.embeddingModelName())
                .build();
    }

    /** LLM interno: reformular en el RAG correctivo (CRAG) y redactar la respuesta del chat. {@code @Lazy}: se construye en el primer uso. */
    @Bean
    @Lazy
    public ChatModel chatModel(LlmProperties props,
                               @Value("${GEMINI_API_KEY:}") String apiKey) {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(props.chatModelName())
                .temperature(props.temperature())
                .timeout(props.timeout())
                .build();
    }
}
