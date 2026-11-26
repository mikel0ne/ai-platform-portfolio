package io.github.mikel0ne.platform.commons.cache;

import dev.langchain4j.model.chat.request.ChatRequest;

@FunctionalInterface
public interface CacheHitListener {

    CacheHitListener NO_OP = (request, cachedText) -> {
    };

    void onCacheHit(ChatRequest request, String cachedText);
}
