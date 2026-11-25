package io.github.mikel0ne.l1copilot.observability;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import java.util.List;

import io.opentelemetry.sdk.trace.data.SpanData;

final class LangfuseTestTracer implements AutoCloseable {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(new LangfuseTraceAttributesProcessor())
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();

    Tracer tracer() {
        return provider.get("test");
    }

    List<SpanData> finishedSpans() {
        return exporter.getFinishedSpanItems();
    }

    SpanData spanNamed(String name) {
        return finishedSpans().stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No span named '" + name
                        + "' was exported. Exported: " + finishedSpans().stream().map(SpanData::getName).toList()));
    }

    @Override
    public void close() {
        provider.close();
    }

    private static final class NoRestClientBuilder
            implements org.springframework.beans.factory.ObjectProvider<org.springframework.web.client.RestClient.Builder> {

        @Override
        public org.springframework.web.client.RestClient.Builder getObject() {
            throw new AssertionError("The score gateway must not build an HTTP client while Langfuse is disabled");
        }

        @Override
        public org.springframework.web.client.RestClient.Builder getObject(Object... args) {
            return getObject();
        }

        @Override
        public org.springframework.web.client.RestClient.Builder getIfAvailable() {
            return null;
        }

        @Override
        public org.springframework.web.client.RestClient.Builder getIfUnique() {
            return null;
        }
    }

    static LangfuseProperties properties(boolean captureContent) {
        return new LangfuseProperties(true, "http://langfuse.test/api/public/otel",
                "pk-test", "sk-test", "test", "0.0.0-test", 1.0, captureContent);
    }

    static LangfuseScoreGateway disabledScoreGateway() {
        LangfuseProperties disabled = new LangfuseProperties(false, null, null, null,
                null, null, null, null);
        return new LangfuseScoreGateway(disabled, new NoRestClientBuilder());
    }
}
