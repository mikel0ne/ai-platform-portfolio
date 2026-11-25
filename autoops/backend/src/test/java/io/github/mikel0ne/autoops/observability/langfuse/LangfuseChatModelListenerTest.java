package io.github.mikel0ne.autoops.observability.langfuse;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// No network and no Gemini: the models are doubles, invoked through chat(ChatRequest), which fires the listeners.
class LangfuseChatModelListenerTest {

    private static final String MODEL = "gemini-3.1-flash-lite";

    @Test
    void a_real_call_emits_a_generation_with_model_prompt_answer_and_tokens() {
        LangfuseTestTracing t = LangfuseTestTracing.recording();
        ChatModel model = new FakeChatModel(new LangfuseChatModelListener(t.tracing(), "internal"));

        model.chat(ChatRequest.builder()
                .messages(UserMessage.from("what is the probes policy?"))
                .temperature(0.2)
                .build());

        SpanData generation = t.span("gemini.chat");
        assertThat(attr(generation, "langfuse.observation.type")).isEqualTo("generation");
        assertThat(attr(generation, "langfuse.observation.model.name")).isEqualTo(MODEL);
        assertThat(attr(generation, "langfuse.observation.metadata.role")).isEqualTo("internal");
        assertThat(attr(generation, "langfuse.observation.input")).contains("probes policy");
        assertThat(attr(generation, "langfuse.observation.output")).isEqualTo("model answer");
        assertThat(attr(generation, "langfuse.observation.usage_details"))
                .isEqualTo("{\"input\":11,\"output\":7,\"total\":18}");
        assertThat(attr(generation, "langfuse.observation.model.parameters")).contains("\"temperature\":0.2");
    }

    @Test
    void with_content_capture_off_neither_prompt_nor_answer_travel_but_tokens_do() {
        LangfuseTestTracing t = LangfuseTestTracing.recordingWithoutContent();
        ChatModel model = new FakeChatModel(new LangfuseChatModelListener(t.tracing(), "internal"));

        model.chat("sensitive data");

        SpanData generation = t.span("gemini.chat");
        assertThat(attr(generation, "langfuse.observation.input")).isNull();
        assertThat(attr(generation, "langfuse.observation.output")).isNull();
        assertThat(attr(generation, "langfuse.observation.usage_details")).isNotNull();
    }

    @Test
    void a_model_failure_closes_the_generation_marked_as_error() {
        LangfuseTestTracing t = LangfuseTestTracing.recording();
        ChatModel model = new FailingChatModel(new LangfuseChatModelListener(t.tracing(), "internal"));

        assertThatThrownBy(() -> model.chat("failing query")).isInstanceOf(RuntimeException.class);

        SpanData generation = t.span("gemini.chat");
        assertThat(attr(generation, "langfuse.observation.level")).isEqualTo("ERROR");
        assertThat(generation.getEvents()).isNotEmpty();
        assertThat(generation.getStatus()).isNotEqualTo(StatusData.unset());
    }

    // A decorator that answers by itself (a cache hit) never reaches the delegate: no listener, no generation.
    @Test
    void a_decorator_that_skips_the_delegate_emits_no_generation() {
        LangfuseTestTracing t = LangfuseTestTracing.recording();
        ChatModel delegate = new FakeChatModel(new LangfuseChatModelListener(t.tracing(), "internal"));
        ChatModel shortCircuit = new ShortCircuitingChatModel(delegate, "already known answer");

        String answer = shortCircuit.chat("same query as before");

        assertThat(answer).isEqualTo("already known answer");
        assertThat(t.spans()).isEmpty();
    }

    private static String attr(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }

    private record FakeChatModel(ChatModelListener listener) implements ChatModel {

        @Override
        public List<ChatModelListener> listeners() {
            return List.of(listener);
        }

        @Override
        public ModelProvider provider() {
            return ModelProvider.GOOGLE_AI_GEMINI;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("model answer"))
                    .modelName(MODEL)
                    .tokenUsage(new TokenUsage(11, 7, 18))
                    .build();
        }
    }

    private record FailingChatModel(ChatModelListener listener) implements ChatModel {

        @Override
        public List<ChatModelListener> listeners() {
            return List.of(listener);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            throw new RuntimeException("429 RESOURCE_EXHAUSTED");
        }
    }

    private record ShortCircuitingChatModel(ChatModel delegate, String canned) implements ChatModel {

        @Override
        public ChatResponse chat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from(canned)).build();
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            return chat(request);
        }
    }
}
