package io.github.mikel0ne.l1copilot.observability;

import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.Verdict;
import io.github.mikel0ne.platform.commons.langfuse.LangfuseAttributes;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LangfuseTurnTracer {

    static final String SPAN_NAME = "chat.turn";
    static final String MDC_TRACE_ID = "traceId";

    private static final List<String> BASE_TAGS = List.of("l1-copilot", "agent");

    private final Tracer tracer;
    private final LangfuseProperties properties;
    private final LangfuseScoreGateway scores;

    public TurnTrace start(String userId, String conversationId, String message) {
        Span span = tracer.spanBuilder(SPAN_NAME)
                .setAttribute(LangfuseAttributes.TRACE_NAME, SPAN_NAME)
                .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, LangfuseAttributes.TYPE_AGENT)
                .setAttribute(LangfuseAttributes.SESSION_ID, String.valueOf(conversationId))
                .setAttribute(LangfuseAttributes.USER_ID, String.valueOf(userId))
                .setAttribute(LangfuseAttributes.ENVIRONMENT, properties.environment())
                .setAttribute(LangfuseAttributes.RELEASE, properties.release())
                .setAttribute(LangfuseAttributes.TRACE_TAGS, BASE_TAGS)
                .startSpan();
        if (properties.isCaptureContent()) {
            span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, String.valueOf(message));
        }
        return new TurnTrace(span, userId, conversationId);
    }

    public final class TurnTrace implements AutoCloseable {

        private final Span span;
        private final Scope scope;
        private final String traceId;

        private TurnTrace(Span span, String userId, String conversationId) {
            this.span = span;
            Baggage baggage = Baggage.builder()
                    .put(LangfuseTraceAttributesProcessor.SESSION_BAGGAGE, String.valueOf(conversationId))
                    .put(LangfuseTraceAttributesProcessor.USER_BAGGAGE, String.valueOf(userId))
                    .build();
            this.scope = baggage.storeInContext(Context.current().with(span)).makeCurrent();
            this.traceId = span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : null;
            if (traceId != null) {
                MDC.put(MDC_TRACE_ID, traceId);
            }
        }

        public String traceId() {
            return traceId;
        }

        public void complete(SupportState state, String reply, int ordersShown, int ticketsCreated) {
            span.setAttribute(LangfuseAttributes.TRACE_TAGS,
                    List.of("l1-copilot", "agent", state.intent().name()));
            metadata("intent", state.intent().name());
            metadata("awaiting", state.awaiting().name());
            metadata("verdict", state.verdict().name());
            metadata("orderId", String.valueOf(state.orderId().orElse(null)));
            metadata("resolved", String.valueOf(state.resolved()));
            metadata("treatment", state.treatment().name());
            metadata("ordersShown", String.valueOf(ordersShown));
            metadata("ticketsCreated", String.valueOf(ticketsCreated));
            if (properties.isCaptureContent()) {
                span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, String.valueOf(reply));
            }
            if (state.verdict() != Verdict.NONE) {
                scores.sendAsync(traceId, "verify_verdict", state.verdict().name(),
                        LangfuseScoreGateway.CATEGORICAL, null);
            }
            scores.sendAsync(traceId, "resolved", state.resolved() ? 1 : 0,
                    LangfuseScoreGateway.BOOLEAN, null);
        }

        public void fail(Throwable error) {
            span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, LangfuseAttributes.LEVEL_ERROR);
            span.recordException(error);
        }

        @Override
        public void close() {
            MDC.remove(MDC_TRACE_ID);
            scope.close();
            span.end();
        }

        private void metadata(String key, String value) {
            span.setAttribute(LangfuseAttributes.TRACE_METADATA_PREFIX + key, value);
        }
    }
}
