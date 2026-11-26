package io.github.mikel0ne.autoops.observability.langfuse;

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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

// Plain OTel SDK, not registered in GlobalOpenTelemetry: only what the hooks build is exported.
@Configuration
@Slf4j
public class LangfuseTracingConfig {


    private static final String INSTRUMENTATION_NAME = "io.github.mikel0ne.autoops.langfuse";

    // Without it OTEL data can take up to 10 minutes to show up: it selects the v4 data model.
    private static final String INGESTION_VERSION_HEADER = "x-langfuse-ingestion-version";

    // destroyMethod: the batch processor buffers, so an unordered shutdown loses the last traces.
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "autoops.langfuse", name = "enabled", havingValue = "true")
    public OpenTelemetrySdk langfuseSdk(LangfuseProperties props) {
        if (!props.hasCredentials()) {
            log.warn("autoops.langfuse.enabled=true but the project keys are missing: "
                    + "exporting to {} will fail with 401 (telemetry is best-effort)", props.tracesUrl());
        }

        SpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(props.tracesUrl())
                .addHeader("Authorization", basicAuth(props))
                .addHeader(INGESTION_VERSION_HEADER, "4")
                .setTimeout(props.timeout())
                .build();

        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), "autoops-mcp",
                AttributeKey.stringKey("service.version"), props.release(),
                AttributeKey.stringKey("deployment.environment.name"), props.environment())));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.traceIdRatioBased(props.sampleRatio()))
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();

        log.info("Langfuse enabled: traces -> {} (environment {}, release {})",
                props.tracesUrl(), props.environment(), props.release());
        return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }

    @Bean
    public Tracer langfuseTracer(ObjectProvider<OpenTelemetrySdk> sdk) {
        OpenTelemetrySdk present = sdk.getIfAvailable();
        return present == null
                ? OpenTelemetry.noop().getTracer(INSTRUMENTATION_NAME)
                : present.getTracer(INSTRUMENTATION_NAME);
    }

    @Bean
    public RestClient langfuseApiClient(LangfuseProperties props) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient(props));
        factory.setReadTimeout(props.timeout());
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.apiBaseUrl())
                .defaultHeader("Authorization", basicAuth(props))
                .build();
    }

    // The JDK client offers `Upgrade: h2c` on plain HTTP and Langfuse answers by closing the connection.
    static final HttpClient.Version API_HTTP_VERSION = HttpClient.Version.HTTP_1_1;

    static HttpClient httpClient(LangfuseProperties props) {
        return HttpClient.newBuilder()
                .version(API_HTTP_VERSION)
                .connectTimeout(props.timeout())
                .build();
    }

    private static String basicAuth(LangfuseProperties props) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (props.publicKey() + ":" + props.secretKey()).getBytes(StandardCharsets.UTF_8));
    }
}
