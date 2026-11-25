package io.github.mikel0ne.autoops.observability.langfuse;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link LangfuseTracing} over an in-memory exporter: instrumentation is verified without network, Spring
 * context or model. {@code SimpleSpanProcessor} so spans are available as soon as they end.
 */
public final class LangfuseTestTracing {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider provider;
    private final LangfuseTracing tracing;

    private LangfuseTestTracing(boolean captureContent) {
        this.provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = provider.get("test");
        this.tracing = new LangfuseTracing(tracer, properties(captureContent));
    }

    public static LangfuseTestTracing recording() {
        return new LangfuseTestTracing(true);
    }

    public static LangfuseTestTracing recordingWithoutContent() {
        return new LangfuseTestTracing(false);
    }

    public static LangfuseTracing noop() {
        return new LangfuseTracing(OpenTelemetry.noop().getTracer("test"), properties(true));
    }

    // Disabled gateway: send() returns before touching the client.
    public static LangfuseScoreGateway noopScores() {
        return new LangfuseScoreGateway(neverResolved(), properties(true));
    }

    private static ObjectProvider<RestClient> neverResolved() {
        return new ObjectProvider<>() {
            @Override
            public RestClient getObject() {
                throw new AssertionError("A disabled score gateway must not resolve the client");
            }

            @Override
            public RestClient getObject(Object... args) {
                return getObject();
            }
        };
    }

    private static LangfuseProperties properties(boolean captureContent) {
        return new LangfuseProperties(false, "http://localhost:1/api/public/otel", null, "", "",
                "test", "0.0.0-test", 1.0, captureContent, Duration.ofSeconds(1));
    }

    public LangfuseTracing tracing() {
        return tracing;
    }

    public List<SpanData> spans() {
        provider.forceFlush().join(5, TimeUnit.SECONDS);
        return exporter.getFinishedSpanItems();
    }

    public List<String> spanNames() {
        return spans().stream().map(SpanData::getName).toList();
    }

    public SpanData span(String name) {
        return spans().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span '" + name + "' was not emitted; got " + spanNames()));
    }

    public Map<String, String> parentNames() {
        Map<String, String> byId = spans().stream()
                .collect(Collectors.toMap(s -> s.getSpanContext().getSpanId(), SpanData::getName, (a, b) -> a));
        return spans().stream().collect(Collectors.toMap(
                SpanData::getName,
                (Function<SpanData, String>) s -> byId.getOrDefault(s.getParentSpanId(), "<root>"),
                (a, b) -> a));
    }
}
