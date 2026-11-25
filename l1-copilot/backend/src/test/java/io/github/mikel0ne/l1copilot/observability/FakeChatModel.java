package io.github.mikel0ne.l1copilot.observability;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;

class FakeChatModel implements ChatModel {

    static final String REPLY = "respuesta del modelo";
    static final TokenUsage USAGE = new TokenUsage(11, 7, 18);

    private final List<ChatModelListener> listeners;
    private final RuntimeException failure;

    FakeChatModel(ChatModelListener listener) {
        this(listener, null);
    }

    FakeChatModel(ChatModelListener listener, RuntimeException failure) {
        this.listeners = List.of(listener);
        this.failure = failure;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        if (failure != null) {
            throw failure;
        }
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(REPLY))
                .tokenUsage(USAGE)
                .build();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return listeners;
    }
}
