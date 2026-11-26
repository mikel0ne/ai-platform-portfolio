package io.github.mikel0ne.platform.commons.langfuse;

import dev.langchain4j.model.chat.request.ChatRequest;
import io.github.mikel0ne.platform.commons.cache.CacheHitListener;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LangfuseCacheHitTracer implements CacheHitListener {

    static final String SPAN_NAME = "gemini.chat (cache)";

    private static final String NO_USAGE = "{\"input\":0,\"output\":0,\"total\":0}";
    private static final String NO_COST = "{\"total\":0}";

    private final Tracer tracer;
    private final String role;
    private final boolean captureContent;

    // Zero tokens and cost: a cache hit consumed no quota and must not inflate the spend.
    @Override
    public void onCacheHit(ChatRequest request, String cachedText) {
        Span span = tracer.spanBuilder(SPAN_NAME)
                .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, LangfuseAttributes.TYPE_GENERATION)
                .setAttribute(LangfuseAttributes.MODEL_NAME, String.valueOf(request.modelName()))
                .setAttribute(LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "role", role)
                .setAttribute(LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "cache", "HIT")
                .setAttribute(LangfuseAttributes.USAGE_DETAILS, NO_USAGE)
                .setAttribute(LangfuseAttributes.COST_DETAILS, NO_COST)
                .startSpan();
        if (captureContent) {
            span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, String.valueOf(request.messages()));
            span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, cachedText);
        }
        span.end();
    }
}
