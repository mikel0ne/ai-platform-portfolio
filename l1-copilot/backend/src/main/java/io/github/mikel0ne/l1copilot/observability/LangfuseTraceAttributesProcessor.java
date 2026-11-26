package io.github.mikel0ne.l1copilot.observability;

import io.github.mikel0ne.platform.commons.langfuse.LangfuseAttributes;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

final class LangfuseTraceAttributesProcessor implements SpanProcessor {

    static final String SESSION_BAGGAGE = "langfuse.session.id";
    static final String USER_BAGGAGE = "langfuse.user.id";

    // Langfuse v4 evaluates these per observation instead of inheriting them from the root span.
    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        Baggage baggage = Baggage.fromContext(parentContext);
        copy(baggage, SESSION_BAGGAGE, span, LangfuseAttributes.SESSION_ID);
        copy(baggage, USER_BAGGAGE, span, LangfuseAttributes.USER_ID);
    }

    private static void copy(Baggage baggage, String key, ReadWriteSpan span, AttributeKey<String> attribute) {
        String value = baggage.getEntryValue(key);
        if (value != null) {
            span.setAttribute(attribute, value);
        }
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }
}
