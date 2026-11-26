package io.github.mikel0ne.l1copilot.observability;

import io.github.mikel0ne.platform.commons.langfuse.LangfuseAttributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseSessionPropagationTest {

    private LangfuseTestTracer tracing;
    private LangfuseTurnTracer turnTracer;

    @BeforeEach
    void setUp() {
        tracing = new LangfuseTestTracer();
        turnTracer = new LangfuseTurnTracer(tracing.tracer(),
                LangfuseTestTracer.properties(true), LangfuseTestTracer.disabledScoreGateway());
    }

    @AfterEach
    void tearDown() {
        tracing.close();
    }

    @Test
    @DisplayName("sessionId y userId llegan a todos los spans del turno, no solo a la raiz")
    void sessionAndUserReachEveryDescendantSpan() {
        try (LangfuseTurnTracer.TurnTrace ignored = turnTracer.start("u1", "conv-42", "hola")) {
            Span node = tracing.tracer().spanBuilder("router").startSpan();
            try (var scope = node.makeCurrent()) {
                tracing.tracer().spanBuilder("gemini.chat").startSpan().end();
            }
            node.end();
        }

        assertThat(tracing.finishedSpans()).hasSize(3);
        for (SpanData span : tracing.finishedSpans()) {
            assertThat(attribute(span, LangfuseAttributes.SESSION_ID)).as(span.getName()).isEqualTo("conv-42");
            assertThat(attribute(span, LangfuseAttributes.USER_ID)).as(span.getName()).isEqualTo("u1");
        }
    }

    @Test
    @DisplayName("Un span fuera de un turno no recibe sessionId ni userId")
    void spanOutsideATurnIsUntouched() {
        tracing.tracer().spanBuilder("suelto").startSpan().end();

        SpanData span = tracing.spanNamed("suelto");
        assertThat(attribute(span, LangfuseAttributes.SESSION_ID)).isNull();
        assertThat(attribute(span, LangfuseAttributes.USER_ID)).isNull();
    }

    @Test
    @DisplayName("Al cerrar el turno el contexto se restaura y el siguiente span no hereda la sesion")
    void contextIsRestoredAfterTheTurn() {
        try (LangfuseTurnTracer.TurnTrace ignored = turnTracer.start("u1", "conv-42", "hola")) {
            assertThat(Span.current().getSpanContext().isValid()).isTrue();
        }
        tracing.tracer().spanBuilder("despues").startSpan().end();

        assertThat(attribute(tracing.spanNamed("despues"), LangfuseAttributes.SESSION_ID)).isNull();
    }

    private static String attribute(SpanData span, String key) {
        return attribute(span, AttributeKey.stringKey(key));
    }

    private static String attribute(SpanData span, AttributeKey<String> key) {
        return span.getAttributes().get(key);
    }
}
