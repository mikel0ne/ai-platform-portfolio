package io.github.mikel0ne.autoops.observability.langfuse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// Scores go as score-create events of POST /api/public/ingestion: in the cluster's events_only mode
// POST /api/public/scores answers 404, and a misnamed field becomes a silent 207.
class LangfuseScoreGatewayTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void the_batch_carries_the_schema_envelope_and_event_type() throws Exception {
        var batch = LangfuseScoreGateway.batchFor(TRACE_ID,
                List.of(LangfuseScoreGateway.categorical("rag_confidence", "high")),
                "k8s", Instant.parse("2026-11-25T15:20:31.123456Z"));

        JsonNode root = json.readTree(json.writeValueAsString(batch));
        assertThat(root.has("batch")).isTrue();
        JsonNode event = root.get("batch").get(0);
        assertThat(event.get("type").asText()).isEqualTo("score-create");
        assertThat(event.get("timestamp").asText()).isEqualTo("2026-11-25T15:20:31.123Z");
        assertThat(UUID.fromString(event.get("id").asText())).isNotNull();
        assertThat(event.has("body")).isTrue();
    }

    @Test
    void the_event_body_carries_trace_id_name_value_type_and_environment() throws Exception {
        var batch = LangfuseScoreGateway.batchFor(TRACE_ID,
                List.of(LangfuseScoreGateway.categorical("rag_confidence", "high")), "k8s", Instant.now());

        JsonNode body = bodyOf(batch, 0);
        assertThat(body.get("traceId").asText()).isEqualTo(TRACE_ID);
        assertThat(body.get("name").asText()).isEqualTo("rag_confidence");
        assertThat(body.get("value").asText()).isEqualTo("high");
        assertThat(body.get("dataType").asText()).isEqualTo("CATEGORICAL");
        assertThat(body.get("environment").asText()).isEqualTo("k8s");
    }

    @Test
    void a_categorical_travels_as_string_and_a_numeric_as_number() throws Exception {
        var batch = LangfuseScoreGateway.batchFor(TRACE_ID, List.of(
                LangfuseScoreGateway.categorical("rag_confidence", "high"),
                LangfuseScoreGateway.numeric("crag_iterations", 2)), "k8s", Instant.now());

        assertThat(bodyOf(batch, 0).get("value").isTextual()).isTrue();
        assertThat(bodyOf(batch, 1).get("value").isNumber()).isTrue();
        assertThat(bodyOf(batch, 1).get("value").asInt()).isEqualTo(2);
    }

    @Test
    void a_boolean_travels_as_one_or_zero_never_as_a_json_boolean() throws Exception {
        var batch = LangfuseScoreGateway.batchFor(TRACE_ID,
                List.of(LangfuseScoreGateway.bool("resolved", true)), "k8s", Instant.now());

        JsonNode body = bodyOf(batch, 0);
        assertThat(body.get("value").isBoolean()).isFalse();
        assertThat(body.get("value").asInt()).isEqualTo(1);
        assertThat(body.get("dataType").asText()).isEqualTo("BOOLEAN");
    }

    @Test
    void optional_fields_without_value_are_omitted() throws Exception {
        var batch = LangfuseScoreGateway.batchFor(TRACE_ID,
                List.of(LangfuseScoreGateway.numeric("crag_iterations", 0)), "local", Instant.now());

        assertThat(bodyOf(batch, 0).has("comment")).isFalse();
    }

    // Without a body id Langfuse answers 207 as if all went well and drops the score.
    @Test
    void every_score_body_has_its_own_id_distinct_from_the_event_id() throws Exception {
        var batch = LangfuseScoreGateway.batchFor(TRACE_ID, List.of(
                LangfuseScoreGateway.categorical("rag_confidence", "high"),
                LangfuseScoreGateway.numeric("crag_iterations", 1)), "k8s", Instant.now());

        JsonNode events = json.readTree(json.writeValueAsString(batch)).get("batch");
        String id0 = events.get(0).get("body").get("id").asText();
        String id1 = events.get(1).get("body").get("id").asText();
        assertThat(UUID.fromString(id0)).isNotNull();
        assertThat(id0).isNotEqualTo(id1);
        assertThat(id0).isNotEqualTo(events.get(0).get("id").asText());
        assertThat(events.get(0).get("id").asText()).isNotEqualTo(events.get(1).get("id").asText());
    }

    @Test
    void it_posts_the_batch_to_the_ingestion_endpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://langfuse.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://langfuse.test/api/public/ingestion"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(allOf(
                        containsString("\"batch\""),
                        containsString("\"type\":\"score-create\""),
                        containsString("\"traceId\":\"" + TRACE_ID + "\""),
                        containsString("\"name\":\"rag_confidence\""),
                        containsString("\"value\":\"high\""),
                        containsString("\"dataType\":\"CATEGORICAL\""),
                        containsString("\"name\":\"crag_iterations\""))))
                .andRespond(withSuccess("{\"successes\":[],\"errors\":[]}", MediaType.APPLICATION_JSON));

        gateway(builder).send(TRACE_ID, List.of(
                LangfuseScoreGateway.categorical("rag_confidence", "high"),
                LangfuseScoreGateway.numeric("crag_iterations", 1)));

        server.verify();
    }

    @Test
    void an_endpoint_failure_does_not_propagate() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://langfuse.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://langfuse.test/api/public/ingestion")).andRespond(withServerError());

        assertThatCode(() -> gateway(builder)
                .send(TRACE_ID, List.of(LangfuseScoreGateway.categorical("rag_confidence", "none"))))
                .doesNotThrowAnyException();
    }

    // A 207 with rejected events is a 2xx: it is logged, not retried and never thrown.
    @Test
    void a_207_with_rejected_events_does_not_propagate() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://langfuse.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://langfuse.test/api/public/ingestion"))
                .andRespond(withSuccess("{\"successes\":[],\"errors\":[{\"id\":\"x\",\"status\":400}]}",
                        MediaType.APPLICATION_JSON));

        assertThatCode(() -> gateway(builder)
                .send(TRACE_ID, List.of(LangfuseScoreGateway.categorical("rag_confidence", "high"))))
                .doesNotThrowAnyException();

        server.verify();
    }

    @Test
    void when_disabled_it_sends_nothing() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://langfuse.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        new LangfuseScoreGateway(provider(builder.build()), props(false))
                .send(TRACE_ID, List.of(LangfuseScoreGateway.categorical("rag_confidence", "high")));

        server.verify();
    }

    @Test
    void without_a_trace_id_it_sends_nothing() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://langfuse.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        gateway(builder).send("", List.of(LangfuseScoreGateway.categorical("rag_confidence", "high")));

        server.verify();
    }

    private JsonNode bodyOf(LangfuseScoreGateway.IngestionBatch batch, int index) throws Exception {
        return json.readTree(json.writeValueAsString(batch)).get("batch").get(index).get("body");
    }

    private static LangfuseScoreGateway gateway(RestClient.Builder builder) {
        return new LangfuseScoreGateway(provider(builder.build()), props(true));
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    private static LangfuseProperties props(boolean enabled) {
        return new LangfuseProperties(enabled, "http://langfuse.test/api/public/otel", null,
                "pk-test", "sk-test", "k8s", "0.0.0-test", 1.0, true, Duration.ofSeconds(1));
    }
}
