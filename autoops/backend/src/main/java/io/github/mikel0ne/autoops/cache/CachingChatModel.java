package io.github.mikel0ne.autoops.cache;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

public class CachingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final FileCacheManager cache;

    public CachingChatModel(ChatModel delegate, FileCacheManager cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            return delegate.chat(request);
        }
        String key = FileCacheManager.key(keyMaterial(request));
        return cache.get(key)
                .map(text -> ChatResponse.builder().aiMessage(AiMessage.from(text)).build())
                .orElseGet(() -> {
                    ChatResponse response = delegate.chat(request);
                    AiMessage message = response.aiMessage();
                    if (message != null && message.text() != null && !message.hasToolExecutionRequests()) {
                        cache.put(key, message.text());
                    }
                    return response;
                });
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    // The parameters are part of the key: the same prompt at another temperature or format is another answer.
    static String keyMaterial(ChatRequest request) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : request.messages()) {
            sb.append(message.type()).append('|').append(message).append('\n');
        }
        ChatRequestParameters params = request.parameters();
        if (params != null) {
            sb.append("model=").append(params.modelName())
                    .append("|temp=").append(params.temperature())
                    .append("|topP=").append(params.topP())
                    .append("|maxOut=").append(params.maxOutputTokens())
                    .append("|format=").append(params.responseFormat());
        }
        return sb.toString();
    }
}
