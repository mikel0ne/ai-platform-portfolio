package io.github.mikel0ne.platform.commons.langfuse;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import java.util.List;

public final class TestTracing implements AutoCloseable {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();

    public Tracer tracer() {
        return provider.get("test");
    }

    public List<SpanData> spans() {
        return exporter.getFinishedSpanItems();
    }

    public List<String> spanNames() {
        return spans().stream().map(SpanData::getName).toList();
    }

    public SpanData span(String name) {
        return spans().stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No span named '" + name + "' was exported. Exported: " + spanNames()));
    }

    public static String attribute(SpanData span, AttributeKey<String> key) {
        return span.getAttributes().get(key);
    }

    public static String attribute(SpanData span, String key) {
        return attribute(span, AttributeKey.stringKey(key));
    }

    @Override
    public void close() {
        provider.close();
    }
}
