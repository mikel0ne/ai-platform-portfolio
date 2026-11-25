package io.github.mikel0ne.autoops.observability.langfuse;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LangfusePropertiesTest {

    @Test
    void traces_url_appends_the_signal_path_to_the_otlp_endpoint() {
        assertThat(props("http://langfuse-web.langfuse.svc.cluster.local:3000/api/public/otel").tracesUrl())
                .isEqualTo("http://langfuse-web.langfuse.svc.cluster.local:3000/api/public/otel/v1/traces");
    }

    @Test
    void rest_api_base_is_derived_by_dropping_the_otlp_path() {
        assertThat(props("http://langfuse.local/api/public/otel").apiBaseUrl())
                .isEqualTo("http://langfuse.local");
    }

    @Test
    void a_trailing_slash_in_the_endpoint_does_not_duplicate_separators() {
        assertThat(props("http://langfuse.local/api/public/otel/").tracesUrl())
                .isEqualTo("http://langfuse.local/api/public/otel/v1/traces");
    }

    @Test
    void defaults_are_disabled_with_content_capture_on() {
        var defaults = new LangfuseProperties(null, null, null, null, null, null, null, null, null, null);

        assertThat(defaults.isEnabled()).isFalse();
        assertThat(defaults.isCaptureContent()).isTrue();
        assertThat(defaults.hasCredentials()).isFalse();
        assertThat(defaults.sampleRatio()).isEqualTo(1.0);
        assertThat(defaults.timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.environment()).isEqualTo("local");
    }

    // The JDK client offers `Upgrade: h2c` on plain HTTP and Langfuse closes the connection on it.
    @Test
    void the_api_client_is_forced_to_http_1_1() {
        assertThat(LangfuseTracingConfig.API_HTTP_VERSION).isEqualTo(HttpClient.Version.HTTP_1_1);
    }

    private static LangfuseProperties props(String endpoint) {
        return new LangfuseProperties(true, endpoint, null, "pk", "sk", "k8s", "1.2.3", 1.0, true,
                Duration.ofSeconds(10));
    }
}
