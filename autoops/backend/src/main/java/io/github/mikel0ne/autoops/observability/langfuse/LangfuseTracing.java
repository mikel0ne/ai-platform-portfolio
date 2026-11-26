package io.github.mikel0ne.autoops.observability.langfuse;

import io.github.mikel0ne.platform.commons.langfuse.LangfuseAttributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

// With autoops.langfuse.enabled=false the injected Tracer is a no-op, so call sites never check it.
@Component
@RequiredArgsConstructor
public class LangfuseTracing {

    private final Tracer tracer;
    private final LangfuseProperties props;

    // In Langfuse's v4 data model the trace input/output are the root span's.
    public Span root(String name, String type, String input, List<String> tags) {
        Span span = tracer.spanBuilder(name)
                .setAttribute(LangfuseAttributes.TRACE_NAME, name)
                .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, type)
                .setAttribute(LangfuseAttributes.ENVIRONMENT, props.environment())
                .setAttribute(LangfuseAttributes.RELEASE, props.release())
                .setAttribute(LangfuseAttributes.TRACE_TAGS, tags)
                .startSpan();
        input(span, input);
        return span;
    }

    public Span child(String name, String type) {
        return tracer.spanBuilder(name)
                .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, type)
                .startSpan();
    }

    public void input(Span span, String value) {
        if (props.isCaptureContent() && value != null) {
            span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, value);
        }
    }

    public void output(Span span, String value) {
        if (props.isCaptureContent() && value != null) {
            span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, value);
        }
    }

    public void traceMetadata(Span span, String key, Object value) {
        span.setAttribute(AttributeKey.stringKey(LangfuseAttributes.TRACE_METADATA_PREFIX + key),
                String.valueOf(value));
    }

    public void fail(Span span, Throwable error) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, LangfuseAttributes.LEVEL_ERROR);
        span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, String.valueOf(error.getMessage()));
        span.setStatus(StatusCode.ERROR, String.valueOf(error.getMessage()));
        span.recordException(error);
    }

    public String currentTraceId() {
        return Span.current().getSpanContext().getTraceId();
    }
}
