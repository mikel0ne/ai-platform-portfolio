package io.github.mikel0ne.autoops.observability.langfuse;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.trace.Span;

import java.util.Map;

/**
 * Emits one Langfuse {@code generation} per <b>real</b> model call. The open span travels in the attributes
 * map that {@code ChatModel.chat(ChatRequest)} creates once and shares between request, response and error
 * callbacks on the same thread; its parent is whatever span is current there (the graph node or the root).
 *
 * <p>A decorator that answers without calling the delegate (a cache hit) fires no listener, so it produces no
 * generation: that keeps the trace honest.
 */
public class LangfuseChatModelListener implements ChatModelListener {

    private static final Object SPAN_KEY = new Object();

    private static final String SPAN_NAME = "gemini.chat";

    private final LangfuseTracing tracing;
    private final String role;

    public LangfuseChatModelListener(LangfuseTracing tracing, String role) {
        this.tracing = tracing;
        this.role = role;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        ChatRequest request = ctx.chatRequest();
        Span span = tracing.child(SPAN_NAME, LangfuseAttributes.TYPE_GENERATION);
        span.setAttribute(LangfuseAttributes.MODEL_NAME, String.valueOf(request.modelName()));
        span.setAttribute(LangfuseAttributes.MODEL_PARAMETERS, modelParameters(request));
        span.setAttribute(LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "role", role);
        tracing.input(span, String.valueOf(request.messages()));
        ctx.attributes().put(SPAN_KEY, span);
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        Span span = spanOf(ctx.attributes());
        if (span == null) {
            return;
        }
        String modelName = ctx.chatResponse().modelName();
        if (modelName != null) {
            span.setAttribute(LangfuseAttributes.MODEL_NAME, modelName);
        }
        TokenUsage usage = ctx.chatResponse().tokenUsage();
        if (usage != null) {
            span.setAttribute(LangfuseAttributes.USAGE_DETAILS, usageDetails(usage));
        }
        tracing.output(span, ctx.chatResponse().aiMessage().text());
        span.end();
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        Span span = spanOf(ctx.attributes());
        if (span == null) {
            return;
        }
        tracing.fail(span, ctx.error());
        span.end();
    }

    private static Span spanOf(Map<Object, Object> attributes) {
        return attributes.get(SPAN_KEY) instanceof Span span ? span : null;
    }

    private static String modelParameters(ChatRequest request) {
        return "{\"temperature\":" + request.temperature()
                + ",\"topP\":" + request.topP()
                + ",\"maxOutputTokens\":" + request.maxOutputTokens() + "}";
    }

    private static String usageDetails(TokenUsage usage) {
        return "{\"input\":" + zeroIfNull(usage.inputTokenCount())
                + ",\"output\":" + zeroIfNull(usage.outputTokenCount())
                + ",\"total\":" + zeroIfNull(usage.totalTokenCount()) + "}";
    }

    private static int zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }
}
