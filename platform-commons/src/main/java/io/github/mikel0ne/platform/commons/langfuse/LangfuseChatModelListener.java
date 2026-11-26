package io.github.mikel0ne.platform.commons.langfuse;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.StringJoiner;

// One generation per real model call; a decorator that answers without calling the delegate fires no listener.
@RequiredArgsConstructor
public class LangfuseChatModelListener implements ChatModelListener {

    static final String SPAN_NAME = "gemini.chat";

    private static final Object SPAN_KEY = new Object();

    private final Tracer tracer;
    private final String role;
    private final boolean captureContent;

    @Override
    public void onRequest(ChatModelRequestContext context) {
        ChatRequest request = context.chatRequest();
        Span span = tracer.spanBuilder(SPAN_NAME)
                .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, LangfuseAttributes.TYPE_GENERATION)
                .setAttribute(LangfuseAttributes.MODEL_NAME, String.valueOf(request.modelName()))
                .setAttribute(LangfuseAttributes.MODEL_PARAMETERS, modelParameters(request))
                .setAttribute(LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "role", role)
                .setAttribute(LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "cache", "MISS")
                .startSpan();
        if (captureContent) {
            span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, String.valueOf(request.messages()));
        }
        context.attributes().put(SPAN_KEY, span);
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        Span span = spanOf(context.attributes());
        if (span == null) {
            return;
        }
        String modelName = context.chatResponse().modelName();
        if (modelName != null) {
            span.setAttribute(LangfuseAttributes.MODEL_NAME, modelName);
        }
        TokenUsage usage = context.chatResponse().tokenUsage();
        if (usage != null) {
            span.setAttribute(LangfuseAttributes.USAGE_DETAILS, usageDetails(usage));
        }
        if (captureContent && context.chatResponse().aiMessage() != null
                && context.chatResponse().aiMessage().text() != null) {
            span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, context.chatResponse().aiMessage().text());
        }
        span.end();
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        Span span = spanOf(context.attributes());
        if (span == null) {
            return;
        }
        Throwable error = context.error();
        span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, LangfuseAttributes.LEVEL_ERROR);
        span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, String.valueOf(error.getMessage()));
        span.setStatus(StatusCode.ERROR, String.valueOf(error.getMessage()));
        span.recordException(error);
        span.end();
    }

    private static Span spanOf(Map<Object, Object> attributes) {
        return attributes.get(SPAN_KEY) instanceof Span span ? span : null;
    }

    private static String modelParameters(ChatRequest request) {
        StringJoiner json = new StringJoiner(",", "{", "}");
        addIfPresent(json, "temperature", request.temperature());
        addIfPresent(json, "topP", request.topP());
        addIfPresent(json, "maxOutputTokens", request.maxOutputTokens());
        return json.toString();
    }

    private static void addIfPresent(StringJoiner json, String name, Object value) {
        if (value != null) {
            json.add("\"" + name + "\":" + value);
        }
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
