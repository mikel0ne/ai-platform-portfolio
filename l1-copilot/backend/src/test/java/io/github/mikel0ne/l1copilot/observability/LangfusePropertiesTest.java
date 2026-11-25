package io.github.mikel0ne.l1copilot.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangfusePropertiesTest {

    @Test
    @DisplayName("Deriva la ruta de trazas y la de ingestion del endpoint OTLP base")
    void derivesTracesAndIngestionPaths() {
        LangfuseProperties properties = properties("http://langfuse.local/api/public/otel");

        assertThat(properties.tracesEndpoint())
                .isEqualTo("http://langfuse.local/api/public/otel/v1/traces");
        assertThat(properties.ingestionEndpoint())
                .isEqualTo("http://langfuse.local/api/public/ingestion");
    }

    @Test
    @DisplayName("Una barra final en el endpoint no duplica la barra de la ruta derivada")
    void trailingSlashIsIgnored() {
        LangfuseProperties properties = properties("http://langfuse.local/api/public/otel/");

        assertThat(properties.tracesEndpoint())
                .isEqualTo("http://langfuse.local/api/public/otel/v1/traces");
        assertThat(properties.ingestionEndpoint())
                .isEqualTo("http://langfuse.local/api/public/ingestion");
    }

    @Test
    @DisplayName("Sin configuracion, Langfuse esta desactivado y el contenido se captura")
    void defaultsAreDisabledAndVerbose() {
        LangfuseProperties properties = new LangfuseProperties(null, null, null, null,
                null, null, null, null);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isCaptureContent()).isTrue();
        assertThat(properties.environment()).isEqualTo("local");
        assertThat(properties.sampleRatio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Con Langfuse apagado el tracer no-op no da traceId, asi que no se envia ningun score")
    void noopTracerYieldsNoTraceId() {
        Tracer noop = OpenTelemetry.noop().getTracer(LangfuseTracingConfig.INSTRUMENTATION_NAME);
        LangfuseTurnTracer turnTracer = new LangfuseTurnTracer(noop,
                LangfuseTestTracer.properties(true), LangfuseTestTracer.disabledScoreGateway());

        try (LangfuseTurnTracer.TurnTrace trace = turnTracer.start("u1", "conv-1", "hola")) {
            assertThat(trace.traceId()).isNull();
            assertThat(Span.current().getSpanContext().isValid()).isFalse();
        }
    }

    private static LangfuseProperties properties(String endpoint) {
        return new LangfuseProperties(true, endpoint, "pk-test", "sk-test",
                "test", "0.0.0-test", 1.0, true);
    }
}
