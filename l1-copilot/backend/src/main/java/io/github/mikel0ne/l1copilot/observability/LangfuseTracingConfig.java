package io.github.mikel0ne.l1copilot.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Configuration
@Slf4j
public class LangfuseTracingConfig {


    static final String INSTRUMENTATION_NAME = "io.github.mikel0ne.l1-copilot";

    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(10);

    // close() flushes the BatchSpanProcessor buffer on shutdown.
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "copilot.langfuse", name = "enabled", havingValue = "true")
    public OpenTelemetrySdk langfuseSdk(LangfuseProperties properties) {
        String auth = Base64.getEncoder().encodeToString(
                (properties.publicKey() + ":" + properties.secretKey()).getBytes(StandardCharsets.UTF_8));

        SpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(properties.tracesEndpoint())
                .addHeader("Authorization", "Basic " + auth)
                // Without it OTEL data can take up to 10 minutes to show up in the UI.
                .addHeader("x-langfuse-ingestion-version", "4")
                .setTimeout(EXPORT_TIMEOUT)
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), INSTRUMENTATION_NAME,
                        AttributeKey.stringKey("service.version"), properties.release()))))
                .setSampler(Sampler.traceIdRatioBased(properties.sampleRatio()))
                .addSpanProcessor(new LangfuseTraceAttributesProcessor())
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();

        log.info("Langfuse ENABLED: traces to {} (environment={}, release={}, sampling={}, content={})",
                properties.tracesEndpoint(), properties.environment(), properties.release(),
                properties.sampleRatio(), properties.isCaptureContent() ? "YES" : "NO");

        return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }

    // No-op fallback so call sites never need to check whether Langfuse is enabled.
    @Bean
    public Tracer langfuseTracer(ObjectProvider<OpenTelemetrySdk> sdk) {
        OpenTelemetrySdk available = sdk.getIfAvailable();
        if (available == null) {
            log.info("Langfuse DISABLED (copilot.langfuse.enabled=false): no-op tracer");
            return OpenTelemetry.noop().getTracer(INSTRUMENTATION_NAME);
        }
        return available.getTracer(INSTRUMENTATION_NAME);
    }
}
