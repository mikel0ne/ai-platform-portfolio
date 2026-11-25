package io.github.mikel0ne.autoops.observability.langfuse;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Connection to the cluster's self-hosted Langfuse. Disabled by default: a local run without Langfuse
 * works the same, with a no-op {@code Tracer}.
 *
 * @param endpoint       OTLP base of Langfuse, <b>without</b> {@code /v1/traces} (see {@link #tracesUrl()})
 * @param apiBaseUrl     base of the Langfuse REST API; derived from {@code endpoint} when omitted
 * @param captureContent privacy switch: when false neither prompts nor answers are exported
 * @param timeout        per-request export cap; telemetry is best-effort
 */
@ConfigurationProperties("autoops.langfuse")
public record LangfuseProperties(
        Boolean enabled,
        String endpoint,
        String apiBaseUrl,
        String publicKey,
        String secretKey,
        String environment,
        String release,
        Double sampleRatio,
        Boolean captureContent,
        Duration timeout
) {

    private static final String OTLP_PATH = "/api/public/otel";

    public LangfuseProperties {
        if (enabled == null) enabled = false;
        if (endpoint == null || endpoint.isBlank()) endpoint = "http://langfuse.local" + OTLP_PATH;
        endpoint = trimTrailingSlash(endpoint);
        // OTLP lives under /api/public/otel but the rest of the REST API (scores) does not: one property, two bases.
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) apiBaseUrl = stripOtlpPath(endpoint);
        apiBaseUrl = trimTrailingSlash(apiBaseUrl);
        if (publicKey == null) publicKey = "";
        if (secretKey == null) secretKey = "";
        if (environment == null || environment.isBlank()) environment = "local";
        if (release == null || release.isBlank()) release = "unknown";
        if (sampleRatio == null) sampleRatio = 1.0;
        if (captureContent == null) captureContent = true;
        if (timeout == null) timeout = Duration.ofSeconds(10);
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean isCaptureContent() {
        return Boolean.TRUE.equals(captureContent);
    }

    public boolean hasCredentials() {
        return !publicKey.isBlank() && !secretKey.isBlank();
    }

    // The OTLP/HTTP exporter does not append the signal path when given a full endpoint.
    public String tracesUrl() {
        return endpoint + "/v1/traces";
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String stripOtlpPath(String value) {
        return value.endsWith(OTLP_PATH) ? value.substring(0, value.length() - OTLP_PATH.length()) : value;
    }
}
