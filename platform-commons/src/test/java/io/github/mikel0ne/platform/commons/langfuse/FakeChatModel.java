package io.github.mikel0ne.platform.commons.langfuse;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeChatModel implements ChatModel {

    public static final String REPLY = "model answer";
    public static final String RESPONSE_MODEL = "gemini-response-model";
    public static final TokenUsage USAGE = new TokenUsage(11, 7, 18);

    private final List<ChatModelListener> listeners;
    private final RuntimeException failure;
    private final AtomicInteger calls = new AtomicInteger();

    public FakeChatModel(ChatModelListener listener) {
        this(listener, null);
    }

    public FakeChatModel(ChatModelListener listener, RuntimeException failure) {
        this.listeners = listener == null ? List.of() : List.of(listener);
        this.failure = failure;
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        calls.incrementAndGet();
        if (failure != null) {
            throw failure;
        }
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(REPLY))
                .modelName(RESPONSE_MODEL)
                .tokenUsage(USAGE)
                .build();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return listeners;
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.GOOGLE_AI_GEMINI;
    }
}
