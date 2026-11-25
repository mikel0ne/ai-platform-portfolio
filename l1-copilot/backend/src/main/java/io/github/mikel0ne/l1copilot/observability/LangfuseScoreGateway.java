package io.github.mikel0ne.l1copilot.observability;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.EOFException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
public class LangfuseScoreGateway {

    private static final Logger log = LoggerFactory.getLogger(LangfuseScoreGateway.class);

    public static final String NUMERIC = "NUMERIC";
    public static final String CATEGORICAL = "CATEGORICAL";
    public static final String BOOLEAN = "BOOLEAN";

    // The id inside the body is mandatory: without it Langfuse silently drops the score.
    static final String EVENT_TYPE = "score-create";

    private static final DateTimeFormatter MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LangfuseProperties properties;
    private final RestClient client;

    // The builder is resolved lazily so a start-up with Langfuse disabled behaves exactly as before.
    public LangfuseScoreGateway(LangfuseProperties properties, ObjectProvider<RestClient.Builder> builder) {
        this.properties = properties;
        this.client = properties.isEnabled() ? builder.getObject().requestFactory(new JdkClientHttpRequestFactory(http11Client())).build() : null;
    }

    public void send(String traceId, String name, Object value, String dataType, String comment) {
        if (client == null || traceId == null || traceId.isBlank()) {
            return;
        }
        try {
            byte[] payload = JSON.writeValueAsBytes(newBatch(traceId, name, value, dataType, comment,
                    properties.environment()));
            JsonNode response;
            try {
                response = post(payload);
            } catch (RuntimeException e) {
                if (!isStaleConnection(e)) {
                    throw e;
                }
                response = post(payload);
            }
            int rejected = response == null ? 0 : response.path("errors").size();
            int accepted = response == null ? 0 : response.path("successes").size();
            if (rejected > 0) {
                log.warn("Langfuse rejected score '{}': {}", name, response.path("errors"));
            }
            log.info("Score '{}' sent to Langfuse: {} accepted, {} rejected", name, accepted, rejected);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("Could not send score '{}' to Langfuse", name, e);
        }
    }

    // Langfuse v4 in events_only mode only accepts scores as ingestion events; the JDK client defaults to
    // an h2c upgrade that Langfuse answers by closing the connection, hence HTTP/1.1 and a single retry on EOF.
    private JsonNode post(byte[] payload) {
        return client.post()
                .uri(properties.ingestionEndpoint())
                .header("Authorization", "Basic " + basicAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);
    }

    static HttpClient http11Client() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    static boolean isStaleConnection(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof EOFException) {
                return true;
            }
        }
        return false;
    }

    static Batch newBatch(String traceId, String name, Object value, String dataType, String comment,
                          String environment) {
        return batch(UUID.randomUUID().toString(), UUID.randomUUID().toString(), Instant.now(), traceId, name,
                value, dataType, comment, environment);
    }

    static Batch batch(String eventId, String scoreId, Instant timestamp, String traceId, String name,
                       Object value, String dataType, String comment, String environment) {
        Body body = new Body(scoreId, traceId, name, value, dataType, comment, environment);
        return new Batch(List.of(new Event(eventId, MILLIS.format(timestamp), EVENT_TYPE, body)));
    }

    private String basicAuth() {
        return Base64.getEncoder().encodeToString(
                (properties.publicKey() + ":" + properties.secretKey()).getBytes(StandardCharsets.UTF_8));
    }

    record Batch(List<Event> batch) {
    }

    record Event(String id, String timestamp, String type, Body body) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Body(String id, String traceId, String name, Object value, String dataType, String comment,
                String environment) {
    }
}
