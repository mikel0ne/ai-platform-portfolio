package io.github.mikel0ne.platform.commons.cache;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class CachingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final FileCacheManager cache;
    private final CacheHitListener onHit;

    public CachingChatModel(ChatModel delegate, FileCacheManager cache) {
        this(delegate, cache, CacheHitListener.NO_OP);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            return delegate.chat(request);
        }

        String key = FileCacheManager.key(keyMaterial(request));
        Optional<String> cached = cache.get(key);
        if (cached.isPresent()) {
            log.debug("Cache HIT (key={})", key);
            onHit.onCacheHit(request, cached.get());
            return ChatResponse.builder().aiMessage(AiMessage.from(cached.get())).build();
        }

        log.debug("Cache MISS (key={})", key);
        ChatResponse response = delegate.chat(request);
        AiMessage message = response.aiMessage();
        if (message != null && message.text() != null && !message.hasToolExecutionRequests()) {
            cache.put(key, message.text());
        }
        return response;
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    // The parameters are part of the key: the same prompt at another temperature or format is another answer.
    static String keyMaterial(ChatRequest request) {
        StringBuilder sb = new StringBuilder(1024);
        for (ChatMessage message : request.messages()) {
            sb.append(message.type()).append('|').append(message).append('\n');
        }
        ChatRequestParameters params = request.parameters();
        if (params != null) {
            sb.append("model=").append(params.modelName())
                    .append("|temp=").append(params.temperature())
                    .append("|topP=").append(params.topP())
                    .append("|topK=").append(params.topK())
                    .append("|maxOut=").append(params.maxOutputTokens())
                    .append("|respFormat=").append(params.responseFormat());
        }
        return sb.toString();
    }
}
