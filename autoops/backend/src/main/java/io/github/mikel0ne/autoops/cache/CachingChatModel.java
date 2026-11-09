package io.github.mikel0ne.autoops.cache;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
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
        String key = FileCacheManager.key(request.messages().toString());
        return cache.get(key)
                .map(text -> ChatResponse.builder().aiMessage(AiMessage.from(text)).build())
                .orElseGet(() -> {
                    ChatResponse response = delegate.chat(request);
                    if (response.aiMessage() != null) {
                        cache.put(key, response.aiMessage().text());
                    }
                    return response;
                });
    }
}
