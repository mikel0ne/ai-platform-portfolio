package io.github.mikel0ne.autoops.observability.langfuse;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

// Not POST /api/public/scores: the cluster's Langfuse runs in events_only write mode and answers 404 there.
@Service
@Slf4j
@RequiredArgsConstructor
public class LangfuseScoreGateway {

    private static final String INGESTION_PATH = "/api/public/ingestion";

    private static final String SCORE_CREATE = "score-create";

    public static final String NUMERIC = "NUMERIC";
    public static final String CATEGORICAL = "CATEGORICAL";
    public static final String BOOLEAN = "BOOLEAN";

    // ObjectProvider: with Langfuse off the client is never resolved.
    @Qualifier("langfuseApiClient")
    private final ObjectProvider<RestClient> client;
    private final LangfuseProperties props;

    public void send(String traceId, List<Score> scores) {
        if (!props.isEnabled() || traceId == null || traceId.isBlank() || scores.isEmpty()) {
            log.debug("Scores of trace '{}' not sent (enabled={}, scores={})",
                    traceId, props.isEnabled(), scores.size());
            return;
        }
        try {
            IngestionResponse response = client.getObject().post()
                    .uri(INGESTION_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(batchFor(traceId, scores, props.environment(), Instant.now()))
                    .retrieve()
                    .body(IngestionResponse.class);
            int accepted = response == null || response.successes() == null ? 0 : response.successes().size();
            int rejected = response == null || response.errors() == null ? 0 : response.errors().size();
            log.info("Langfuse: {} scores sent to trace {} (accepted={}, rejected={})",
                    scores.size(), traceId, accepted, rejected);
            if (rejected > 0) {
                log.warn("Langfuse rejected {} of the scores of trace {}: {}", rejected, traceId, response.errors());
            }
        } catch (Exception e) {
            log.warn("Could not send the scores of trace {} to Langfuse: {}", traceId, e.getMessage());
        }
    }

    // Pure and package-private so it can be tested without network.
    static IngestionBatch batchFor(String traceId, List<Score> scores, String environment, Instant now) {
        // The API orders events by this timestamp and requires millisecond resolution at least.
        String timestamp = now.truncatedTo(ChronoUnit.MILLIS).toString();
        return new IngestionBatch(scores.stream()
                .map(s -> new ScoreEvent(
                        UUID.randomUUID().toString(),
                        SCORE_CREATE,
                        timestamp,
                        new ScoreBody(UUID.randomUUID().toString(), traceId, s.name(), s.value(), s.dataType(),
                                s.comment(), environment)))
                .toList());
    }

    public static Score numeric(String name, Number value) {
        return new Score(name, value, NUMERIC, null);
    }

    public static Score categorical(String name, String value) {
        return new Score(name, value, CATEGORICAL, null);
    }

    // The API rejects a JSON boolean here: the value must be 1 or 0, with an explicit dataType.
    public static Score bool(String name, boolean value) {
        return new Score(name, value ? 1 : 0, BOOLEAN, null);
    }

    // value: number for NUMERIC/BOOLEAN, string for CATEGORICAL
    public record Score(String name, Object value, String dataType, String comment) {}

    public record IngestionBatch(List<ScoreEvent> batch) {}

    public record ScoreEvent(String id, String type, String timestamp, ScoreBody body) {}

    // Without a body id Langfuse answers 207 as if all went well and silently drops the score.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScoreBody(String id, String traceId, String name, Object value, String dataType, String comment,
                            String environment) {}

    // 207 carries per-event rejections instead of a 4xx; retrying a partial batch is forbidden by Langfuse.
    public record IngestionResponse(List<Object> successes, List<Object> errors) {}
}
