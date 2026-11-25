package io.github.mikel0ne.l1copilot.observability;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangfuseChatModelListenerTest {

    private LangfuseTestTracer tracing;

    private static final ChatRequestParameters PARAMETERS = DefaultChatRequestParameters.builder()
            .modelName("gemini-3.1-flash-lite")
            .temperature(0.0)
            .build();

    @BeforeEach
    void setUp() {
        tracing = new LangfuseTestTracer();
    }

    @AfterEach
    void tearDown() {
        tracing.close();
    }

    @Test
    @DisplayName("Una llamada al modelo emite un generation con modelo, prompt, respuesta y tokens")
    void realCallEmitsGeneration() {
        ChatModel model = new FakeChatModel(
                new LangfuseChatModelListener(tracing.tracer(), "determinista", true));

        model.chat(request("¿dónde está mi pedido?"));

        SpanData generation = tracing.spanNamed("gemini.chat");
        assertThat(attribute(generation, LangfuseAttributes.OBSERVATION_TYPE))
                .isEqualTo(LangfuseAttributes.TYPE_GENERATION);
        assertThat(attribute(generation, LangfuseAttributes.MODEL_NAME))
                .isEqualTo("gemini-3.1-flash-lite");
        assertThat(attribute(generation, LangfuseAttributes.MODEL_PARAMETERS))
                .isEqualTo("{\"temperature\":0.0}");
        assertThat(attribute(generation, LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "role"))
                .isEqualTo("determinista");
        assertThat(attribute(generation, LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "cache"))
                .isEqualTo("MISS");
        assertThat(attribute(generation, LangfuseAttributes.USAGE_DETAILS))
                .isEqualTo("{\"input\":11,\"output\":7,\"total\":18}");
        assertThat(attribute(generation, LangfuseAttributes.OBSERVATION_INPUT))
                .contains("¿dónde está mi pedido?");
        assertThat(attribute(generation, LangfuseAttributes.OBSERVATION_OUTPUT))
                .isEqualTo(FakeChatModel.REPLY);
    }

    @Test
    @DisplayName("Con capture-content=false el generation va sin prompt ni respuesta, pero con tokens")
    void contentCanBeSuppressed() {
        ChatModel model = new FakeChatModel(
                new LangfuseChatModelListener(tracing.tracer(), "determinista", false));

        model.chat(request("mi dirección es Calle Falsa 123"));

        SpanData generation = tracing.spanNamed("gemini.chat");
        assertThat(attribute(generation, LangfuseAttributes.OBSERVATION_INPUT)).isNull();
        assertThat(attribute(generation, LangfuseAttributes.OBSERVATION_OUTPUT)).isNull();
        assertThat(attribute(generation, LangfuseAttributes.USAGE_DETAILS)).isNotNull();
    }

    @Test
    @DisplayName("Un fallo del modelo cierra el generation marcado como ERROR")
    void failureClosesTheGeneration() {
        ChatModel model = new FakeChatModel(
                new LangfuseChatModelListener(tracing.tracer(), "respuesta", true),
                new IllegalStateException("cuota agotada"));

        assertThatThrownBy(() -> model.chat(request("hola")))
                .isInstanceOf(IllegalStateException.class);

        SpanData generation = tracing.spanNamed("gemini.chat");
        assertThat(attribute(generation, LangfuseAttributes.OBSERVATION_LEVEL))
                .isEqualTo(LangfuseAttributes.LEVEL_ERROR);
        assertThat(generation.getEvents())
                .extracting(event -> event.getName())
                .contains("exception");
    }

    private static ChatRequest request(String message) {
        return ChatRequest.builder()
                .messages(dev.langchain4j.data.message.UserMessage.from(message))
                .parameters(PARAMETERS)
                .build();
    }

    private static String attribute(SpanData span, String key) {
        return span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(key));
    }
}
