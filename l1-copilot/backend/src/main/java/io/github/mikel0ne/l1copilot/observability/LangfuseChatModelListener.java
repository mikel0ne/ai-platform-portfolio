package io.github.mikel0ne.l1copilot.observability;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

public class LangfuseChatModelListener implements ChatModelListener {

    private static final Object SPAN_ATTRIBUTE = new Object();

    static final String SPAN_NAME = "gemini.chat";

    private final Tracer tracer;
    private final String role;
    private final boolean captureContent;

    public LangfuseChatModelListener(Tracer tracer, String role, boolean captureContent) {
        this.tracer = tracer;
        this.role = role;
        this.captureContent = captureContent;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        Span span = tracer.spanBuilder(SPAN_NAME)
                .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, LangfuseAttributes.TYPE_GENERATION)
                .setAttribute(LangfuseAttributes.MODEL_NAME, String.valueOf(context.chatRequest().modelName()))
                .setAttribute(LangfuseAttributes.MODEL_PARAMETERS,
                        "{\"temperature\":" + context.chatRequest().temperature() + "}")
                .setAttribute(LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "role", role)
                .setAttribute(LangfuseAttributes.OBSERVATION_METADATA_PREFIX + "cache", "MISS")
                .startSpan();
        if (captureContent) {
            span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT,
                    String.valueOf(context.chatRequest().messages()));
        }
        context.attributes().put(SPAN_ATTRIBUTE, span);
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        Span span = (Span) context.attributes().get(SPAN_ATTRIBUTE);
        if (span == null) {
            return;
        }
        TokenUsage usage = context.chatResponse().tokenUsage();
        if (usage != null) {
            span.setAttribute(LangfuseAttributes.USAGE_DETAILS,
                    "{\"input\":" + usage.inputTokenCount()
                            + ",\"output\":" + usage.outputTokenCount()
                            + ",\"total\":" + usage.totalTokenCount() + "}");
        }
        if (captureContent && context.chatResponse().aiMessage() != null) {
            span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT,
                    String.valueOf(context.chatResponse().aiMessage().text()));
        }
        span.end();
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        Span span = (Span) context.attributes().get(SPAN_ATTRIBUTE);
        if (span == null) {
            return;
        }
        span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, LangfuseAttributes.LEVEL_ERROR);
        span.recordException(context.error());
        span.end();
    }
}
