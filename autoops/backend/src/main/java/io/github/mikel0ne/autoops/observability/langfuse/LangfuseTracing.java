package io.github.mikel0ne.autoops.observability.langfuse;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Facade over the {@code Tracer} with Langfuse's vocabulary. Call sites never touch
 * {@link LangfuseAttributes} nor ask whether telemetry is on: with {@code autoops.langfuse.enabled=false}
 * the injected {@code Tracer} is a no-op. Also centralizes the {@code capture-content} privacy switch.
 */
@Component
public class LangfuseTracing {

    private final Tracer tracer;
    private final LangfuseProperties props;

    public LangfuseTracing(Tracer langfuseTracer, LangfuseProperties props) {
        this.tracer = langfuseTracer;
        this.props = props;
    }

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
