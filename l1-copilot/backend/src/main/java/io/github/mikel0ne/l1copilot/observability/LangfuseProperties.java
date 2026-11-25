package io.github.mikel0ne.l1copilot.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "copilot.langfuse")
public record LangfuseProperties(
        Boolean enabled,
        String endpoint,
        String publicKey,
        String secretKey,
        String environment,
        String release,
        Double sampleRatio,
        Boolean captureContent) {

    private static final String OTEL_PATH = "/otel";

    public LangfuseProperties {
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "http://langfuse.local/api/public/otel";
        }
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        if (environment == null || environment.isBlank()) {
            environment = "local";
        }
        if (release == null || release.isBlank()) {
            release = "unknown";
        }
        if (sampleRatio == null) {
            sampleRatio = 1.0;
        }
        if (captureContent == null) {
            captureContent = true;
        }
        if (enabled == null) {
            enabled = false;
        }
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean isCaptureContent() {
        return Boolean.TRUE.equals(captureContent);
    }

    public String tracesEndpoint() {
        return endpoint + "/v1/traces";
    }

    public String ingestionEndpoint() {
        return endpoint.endsWith(OTEL_PATH)
                ? endpoint.substring(0, endpoint.length() - OTEL_PATH.length()) + "/ingestion"
                : endpoint + "/ingestion";
    }
}
