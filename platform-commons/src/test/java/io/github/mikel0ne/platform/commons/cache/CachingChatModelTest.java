package io.github.mikel0ne.platform.commons.cache;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.mikel0ne.platform.commons.langfuse.FakeChatModel;
import io.github.mikel0ne.platform.commons.langfuse.LangfuseCacheHitTracer;
import io.github.mikel0ne.platform.commons.langfuse.LangfuseChatModelListener;
import io.github.mikel0ne.platform.commons.langfuse.TestTracing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CachingChatModelTest {

    private static ChatRequest request(double temperature) {
        return ChatRequest.builder().messages(UserMessage.from("same prompt")).temperature(temperature).build();
    }

    private static ChatRequest withParameters(DefaultChatRequestParameters.Builder<?> parameters) {
        return ChatRequest.builder().messages(UserMessage.from("p")).parameters(parameters.build()).build();
    }

    private static FileCacheManager memoryCache(Path dir) {
        return new FileCacheManager(true, false, dir, Duration.ofHours(1));
    }

    @Test
    @DisplayName("The same prompt with other parameters is another cache entry")
    void sameMessagesWithOtherParametersAreAnotherEntry() {
        assertThat(CachingChatModel.keyMaterial(request(0.0)))
                .isNotEqualTo(CachingChatModel.keyMaterial(request(0.7)))
                .isEqualTo(CachingChatModel.keyMaterial(request(0.0)));
    }

    @Test
    @DisplayName("Model name and topK are part of the key")
    void modelNameAndTopKArePartOfTheKey() {
        ChatRequest base = withParameters(DefaultChatRequestParameters.builder().modelName("m").topK(10));
        ChatRequest otherTopK = withParameters(DefaultChatRequestParameters.builder().modelName("m").topK(20));
        ChatRequest otherModel = withParameters(DefaultChatRequestParameters.builder().modelName("n").topK(10));

        assertThat(CachingChatModel.keyMaterial(base))
                .isNotEqualTo(CachingChatModel.keyMaterial(otherTopK))
                .isNotEqualTo(CachingChatModel.keyMaterial(otherModel));
    }

    @Test
    @DisplayName("The second identical request is served from the cache without calling the model")
    void secondRequestIsServedFromTheCache(@TempDir Path tmp) {
        FakeChatModel delegate = new FakeChatModel(null);
        ChatModel cached = new CachingChatModel(delegate, memoryCache(tmp));

        ChatResponse first = cached.chat(request(0.0));
        ChatResponse second = cached.chat(request(0.0));

        assertThat(delegate.calls()).isEqualTo(1);
        assertThat(first.aiMessage().text()).isEqualTo(FakeChatModel.REPLY);
        assertThat(second.aiMessage().text()).isEqualTo(FakeChatModel.REPLY);
    }

    @Test
    @DisplayName("Requests with tool specifications bypass the cache")
    void toolRequestsBypassTheCache(@TempDir Path tmp) {
        FakeChatModel delegate = new FakeChatModel(null);
        FileCacheManager cache = memoryCache(tmp);
        ChatModel cached = new CachingChatModel(delegate, cache);
        ChatRequest withTools = ChatRequest.builder()
                .messages(UserMessage.from("same prompt"))
                .toolSpecifications(ToolSpecification.builder().name("lookup").build())
                .build();

        cached.chat(withTools);
        cached.chat(withTools);

        assertThat(delegate.calls()).isEqualTo(2);
        assertThat(cache.getStats()).containsEntry("entriesInMemory", 0);
    }

    @Test
    @DisplayName("A disabled cache always goes to the model")
    void disabledCacheAlwaysCallsTheModel(@TempDir Path tmp) {
        FakeChatModel delegate = new FakeChatModel(null);
        ChatModel cached = new CachingChatModel(delegate, new FileCacheManager(false, false, tmp, Duration.ofHours(1)));

        cached.chat(request(0.0));
        cached.chat(request(0.0));

        assertThat(delegate.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("The hit listener is told about hits only, with the cached text")
    void hitListenerIsNotifiedOnHitsOnly(@TempDir Path tmp) {
        List<String> hits = new ArrayList<>();
        ChatModel cached = new CachingChatModel(new FakeChatModel(null), memoryCache(tmp),
                (request, text) -> hits.add(text));

        cached.chat(request(0.0));
        assertThat(hits).isEmpty();

        cached.chat(request(0.0));
        assertThat(hits).containsExactly(FakeChatModel.REPLY);
    }

    @Test
    @DisplayName("Delegates provider, listeners and default parameters to the wrapped model")
    void delegatesTheModelMetadata(@TempDir Path tmp) {
        try (TestTracing tracing = new TestTracing()) {
            FakeChatModel delegate = new FakeChatModel(new LangfuseChatModelListener(tracing.tracer(), "r", true));
            ChatModel cached = new CachingChatModel(delegate, memoryCache(tmp));

            assertThat(cached.provider()).isEqualTo(ModelProvider.GOOGLE_AI_GEMINI);
            assertThat(cached.listeners()).isEqualTo(delegate.listeners());
            assertThat(cached.defaultRequestParameters()).isEqualTo(delegate.defaultRequestParameters());
            assertThat(cached.supportedCapabilities()).isEqualTo(delegate.supportedCapabilities());
        }
    }

    @Test
    @DisplayName("With the Langfuse hit tracer a hit shows as its own span next to the real generation")
    void hitTracerEmitsOneSpanPerHit(@TempDir Path tmp) {
        try (TestTracing tracing = new TestTracing()) {
            LangfuseChatModelListener listener = new LangfuseChatModelListener(tracing.tracer(), "determinista", true);
            ChatModel cached = new CachingChatModel(new FakeChatModel(listener), memoryCache(tmp),
                    new LangfuseCacheHitTracer(tracing.tracer(), "determinista", true));

            cached.chat(request(0.0));
            cached.chat(request(0.0));

            assertThat(tracing.spanNames()).containsExactly("gemini.chat", "gemini.chat (cache)");
        }
    }

    @Test
    @DisplayName("With the no-op hit listener a hit emits no span at all")
    void noOpHitListenerEmitsNothing(@TempDir Path tmp) {
        try (TestTracing tracing = new TestTracing()) {
            LangfuseChatModelListener listener = new LangfuseChatModelListener(tracing.tracer(), "internal", true);
            ChatModel cached = new CachingChatModel(new FakeChatModel(listener), memoryCache(tmp), CacheHitListener.NO_OP);

            cached.chat(request(0.0));
            cached.chat(request(0.0));

            assertThat(tracing.spanNames()).containsExactly("gemini.chat");
        }
    }
}
