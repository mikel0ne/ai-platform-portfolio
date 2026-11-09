package io.github.mikel0ne.autoops.observability;

import io.github.mikel0ne.autoops.observability.model.LogEntry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LokiGatewayTest {

    @Test
    void buildLogQl_namespace_only() {
        assertThat(LokiGateway.buildLogQl("autoops", null, null, null))
                .isEqualTo("{namespace=\"autoops\"}");
    }

    @Test
    void buildLogQl_with_pod_container_and_text() {
        assertThat(LokiGateway.buildLogQl("autoops", "autoops-backend-abc", "app", "ERROR"))
                .isEqualTo("{namespace=\"autoops\",pod=\"autoops-backend-abc\",container=\"app\"} |= \"ERROR\"");
    }

    @Test
    void buildLogQl_escapes_quotes_in_the_text() {
        assertThat(LokiGateway.buildLogQl("autoops", null, null, "dijo \"hola\""))
                .isEqualTo("{namespace=\"autoops\"} |= \"dijo \\\"hola\\\"\"");
    }

    @Test
    void resolveWindow_defaults_to_the_last_15_minutes() {
        LokiGateway.TimeWindow v = LokiGateway.resolveWindow(null, null, null);

        assertThat(Duration.between(v.from(), v.to())).isCloseTo(Duration.ofMinutes(15), Duration.ofSeconds(2));
        assertThat(Duration.between(v.to(), Instant.now())).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void resolveWindow_honours_custom_window_minutes() {
        LokiGateway.TimeWindow v = LokiGateway.resolveWindow(null, null, 5);

        assertThat(Duration.between(v.from(), v.to())).isCloseTo(Duration.ofMinutes(5), Duration.ofSeconds(2));
    }

    @Test
    void resolveWindow_with_explicit_from_and_to_accepts_milliseconds() {
        LokiGateway.TimeWindow v = LokiGateway.resolveWindow(
                "2026-06-26T14:32:07.100Z", "2026-06-26T14:32:07.900Z", null);

        assertThat(v.from()).isEqualTo(Instant.parse("2026-06-26T14:32:07.100Z"));
        assertThat(v.to()).isEqualTo(Instant.parse("2026-06-26T14:32:07.900Z"));
    }

    @Test
    void flatten_merges_and_sorts_several_streams_chronologically() {
        var streamA = new LokiGateway.LokiStream(
                Map.of("namespace", "autoops", "pod", "p1", "container", "app"),
                List.of(List.of("1750949000000000000", "segunda linea")));
        var streamB = new LokiGateway.LokiStream(
                Map.of("namespace", "autoops", "pod", "p2", "container", "app"),
                List.of(List.of("1750948999000000000", "primera linea")));
        var answer = new LokiGateway.LokiResponse("success",
                new LokiGateway.LokiData("streams", List.of(streamA, streamB)));

        List<LogEntry> entries = LokiGateway.flatten(answer);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).line()).isEqualTo("primera linea");
        assertThat(entries.get(0).pod()).isEqualTo("p2");
        assertThat(entries.get(1).line()).isEqualTo("segunda linea");
        assertThat(entries.get(0).timestamp()).isBefore(entries.get(1).timestamp());
    }

    @Test
    void flatten_handles_an_empty_response() {
        assertThat(LokiGateway.flatten(null)).isEmpty();
        assertThat(LokiGateway.flatten(new LokiGateway.LokiResponse("success", null))).isEmpty();
    }

    @Test
    void queryRange_handles_literal_braces_in_logql() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(allOf(
                        containsString("/loki/api/v1/query_range"),
                        containsString("query=%7Bnamespace%3D%22autoops%22%7D"))))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}}",
                        MediaType.APPLICATION_JSON));

        LokiGateway gateway = new LokiGateway(builder.build());

        assertThat(gateway.queryRange("autoops", null, null, null, null, null, 15, 10)).isEmpty();
        server.verify();
    }
}
