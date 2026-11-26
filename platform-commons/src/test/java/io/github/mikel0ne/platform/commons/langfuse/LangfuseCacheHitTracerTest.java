package io.github.mikel0ne.platform.commons.langfuse;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.github.mikel0ne.platform.commons.langfuse.TestTracing.attribute;
import static org.assertj.core.api.Assertions.assertThat;

class LangfuseCacheHitTracerTest {

    private TestTracing tracing;

    @BeforeEach
    void setUp() {
        tracing = new TestTracing();
    }

    @AfterEach
    void tearDown() {
        tracing.close();
    }

    @Test
    @DisplayName("A cache hit is a zero-token, zero-cost generation marked cache=HIT")
    void hitIsAZeroCostGeneration() {
        new LangfuseCacheHitTracer(tracing.tracer(), "determinista", true).onCacheHit(request(), "cached answer");

        SpanData hit = tracing.span("gemini.chat (cache)");
        assertThat(attribute(hit, LangfuseAttributes.OBSERVATION_TYPE)).isEqualTo(LangfuseAttributes.TYPE_GENERATION);
        assertThat(attribute(hit, LangfuseAttributes.MODEL_NAME)).isEqualTo("gemini-3.1-flash-lite");
        assertThat(attribute(hit, LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "role")).isEqualTo("determinista");
        assertThat(attribute(hit, LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "cache")).isEqualTo("HIT");
        assertThat(attribute(hit, LangfuseAttributes.USAGE_DETAILS))
                .isEqualTo("{\"input\":0,\"output\":0,\"total\":0}");
        assertThat(attribute(hit, LangfuseAttributes.COST_DETAILS)).isEqualTo("{\"total\":0}");
        assertThat(attribute(hit, LangfuseAttributes.OBSERVATION_INPUT)).contains("donde esta mi pedido");
        assertThat(attribute(hit, LangfuseAttributes.OBSERVATION_OUTPUT)).isEqualTo("cached answer");
    }

    @Test
    @DisplayName("With capture-content=false the hit carries neither prompt nor answer")
    void contentCanBeSuppressed() {
        new LangfuseCacheHitTracer(tracing.tracer(), "determinista", false).onCacheHit(request(), "cached answer");

        SpanData hit = tracing.span("gemini.chat (cache)");
        assertThat(attribute(hit, LangfuseAttributes.OBSERVATION_INPUT)).isNull();
        assertThat(attribute(hit, LangfuseAttributes.OBSERVATION_OUTPUT)).isNull();
        assertThat(attribute(hit, LangfuseAttributes.USAGE_DETAILS)).isNotNull();
    }

    private static ChatRequest request() {
        return ChatRequest.builder()
                .messages(UserMessage.from("donde esta mi pedido"))
                .parameters(DefaultChatRequestParameters.builder()
                        .modelName("gemini-3.1-flash-lite").temperature(0.0).build())
                .build();
    }
}
