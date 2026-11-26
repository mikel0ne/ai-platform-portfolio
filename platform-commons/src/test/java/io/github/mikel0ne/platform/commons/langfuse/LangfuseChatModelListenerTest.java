package io.github.mikel0ne.platform.commons.langfuse;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.github.mikel0ne.platform.commons.langfuse.TestTracing.attribute;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangfuseChatModelListenerTest {

    private static final String MODEL = "gemini-3.1-flash-lite";

    private static final ChatRequestParameters PARAMETERS = DefaultChatRequestParameters.builder()
            .modelName(MODEL)
            .temperature(0.0)
            .build();

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
    @DisplayName("A real model call emits a generation with model, prompt, answer and tokens")
    void realCallEmitsGeneration() {
        ChatModel model = new FakeChatModel(new LangfuseChatModelListener(tracing.tracer(), "determinista", true));

        model.chat(request("¿dónde está mi pedido?"));

        SpanData generation = tracing.span("gemini.chat");
        assertThat(attribute(generation, LangfuseAttributes.OBSERVATION_TYPE))
                .isEqualTo(LangfuseAttributes.TYPE_GENERATION);
        assertThat(attribute(generation, LangfuseAttributes.MODEL_NAME)).isEqualTo(FakeChatModel.RESPONSE_MODEL);
        assertThat(attribute(generation, LangfuseAttributes.MODEL_PARAMETERS)).isEqualTo("{\"temperature\":0.0}");
        assertThat(attribute(generation, LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "role"))
                .isEqualTo("determinista");
        assertThat(attribute(generation, LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "cache"))
                .isEqualTo("MISS");
        assertThat(attribute(generation, LangfuseAttributes.USAGE_DETAILS))
                .isEqualTo("{\"input\":11,\"output\":7,\"total\":18}");
        assertThat(attribute(generation, LangfuseAttributes.OBSERVATION_INPUT)).contains("¿dónde está mi pedido?");
        assertThat(attribute(generation, LangfuseAttributes.OBSERVATION_OUTPUT)).isEqualTo(FakeChatModel.REPLY);
    }

    @Test
    @DisplayName("The model name comes from the request when the response does not carry one")
    void modelNameFallsBackToTheRequest() {
        ChatModel model = new ChatModelWithoutResponseName(new LangfuseChatModelListener(tracing.tracer(), "r", true));

        model.chat(request("hola"));

        assertThat(attribute(tracing.span("gemini.chat"), LangfuseAttributes.MODEL_NAME)).isEqualTo(MODEL);
    }

    @Test
    @DisplayName("Sampling parameters that are set travel as model parameters")
    void modelParametersIncludeTheSetOnes() {
        ChatModel model = new FakeChatModel(new LangfuseChatModelListener(tracing.tracer(), "internal", true));

        model.chat(ChatRequest.builder()
                .messages(UserMessage.from("what is the probes policy?"))
                .temperature(0.2)
                .topP(0.9)
                .maxOutputTokens(512)
                .build());

        assertThat(attribute(tracing.span("gemini.chat"), LangfuseAttributes.MODEL_PARAMETERS))
                .isEqualTo("{\"temperature\":0.2,\"topP\":0.9,\"maxOutputTokens\":512}");
    }

    @Test
    @DisplayName("With capture-content=false the generation carries no prompt nor answer, but it does carry tokens")
    void contentCanBeSuppressed() {
        ChatModel model = new FakeChatModel(new LangfuseChatModelListener(tracing.tracer(), "determinista", false));

        model.chat(request("mi dirección es Calle Falsa 123"));

        SpanData generation = tracing.span("gemini.chat");
        assertThat(attribute(generation, LangfuseAttributes.OBSERVATION_INPUT)).isNull();
        assertThat(attribute(generation, LangfuseAttributes.OBSERVATION_OUTPUT)).isNull();
        assertThat(attribute(generation, LangfuseAttributes.USAGE_DETAILS)).isNotNull();
    }

    @Test
    @DisplayName("A model failure closes the generation marked as ERROR with its message")
    void failureClosesTheGeneration() {
        ChatModel model = new FakeChatModel(
                new LangfuseChatModelListener(tracing.tracer(), "respuesta", true),
                new IllegalStateException("cuota agotada"));

        assertThatThrownBy(() -> model.chat(request("hola"))).isInstanceOf(IllegalStateException.class);

        SpanData generation = tracing.span("gemini.chat");
        assertThat(attribute(generation, LangfuseAttributes.OBSERVATION_LEVEL))
                .isEqualTo(LangfuseAttributes.LEVEL_ERROR);
        assertThat(attribute(generation, LangfuseAttributes.OBSERVATION_STATUS_MESSAGE)).isEqualTo("cuota agotada");
        assertThat(generation.getStatus()).isNotEqualTo(StatusData.unset());
        assertThat(generation.getEvents()).extracting(event -> event.getName()).contains("exception");
    }

    @Test
    @DisplayName("The generation hangs from the span that is current when the model is called")
    void generationHangsFromTheCurrentSpan() {
        ChatModel model = new FakeChatModel(new LangfuseChatModelListener(tracing.tracer(), "internal", true));
        Span parent = tracing.tracer().spanBuilder("node").startSpan();

        try (Scope ignored = parent.makeCurrent()) {
            model.chat(request("hola"));
        } finally {
            parent.end();
        }

        assertThat(tracing.span("gemini.chat").getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
    }

    @Test
    @DisplayName("A decorator that answers without calling the delegate emits no generation")
    void shortCircuitingDecoratorEmitsNothing() {
        ChatModel delegate = new FakeChatModel(new LangfuseChatModelListener(tracing.tracer(), "internal", true));
        ChatModel shortCircuit = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("already known answer")).build();
            }

            @Override
            public ChatResponse doChat(ChatRequest request) {
                return delegate.doChat(request);
            }
        };

        String answer = shortCircuit.chat("same query as before");

        assertThat(answer).isEqualTo("already known answer");
        assertThat(tracing.spans()).isEmpty();
    }

    private static ChatRequest request(String message) {
        return ChatRequest.builder()
                .messages(UserMessage.from(message))
                .parameters(PARAMETERS)
                .build();
    }

    private static final class ChatModelWithoutResponseName extends FakeChatModel {

        ChatModelWithoutResponseName(ChatModelListener listener) {
            super(listener);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from(REPLY)).tokenUsage(USAGE).build();
        }
    }
}
