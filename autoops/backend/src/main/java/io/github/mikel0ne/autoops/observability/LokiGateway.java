package io.github.mikel0ne.autoops.observability;

import io.github.mikel0ne.autoops.observability.model.LogEntry;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class LokiGateway {

    static final int DEFAULT_LINES = 200;
    static final int MAX_LINES = 1000;
    static final int DEFAULT_WINDOW_MINUTES = 15;
    static final int MAX_WINDOW_MINUTES = 24 * 60;

    private final RestClient client;

    public LokiGateway(@Qualifier("lokiClient") RestClient lokiClient) {
        this.client = lokiClient;
    }

    public List<LogEntry> queryRange(String namespace, String pod, String container, String text,
                                      String from, String to, Integer windowMinutes, Integer lines) {
        TimeWindow window = resolveWindow(from, to, windowMinutes);
        String query = buildLogQl(namespace, pod, container, text);
        int limit = clamp(lines, DEFAULT_LINES, MAX_LINES);

        LokiResponse answer = client.get()
                .uri(uri -> uri.path("/loki/api/v1/query_range")
                        .queryParam("query", "{query}")
                        .queryParam("start", "{start}")
                        .queryParam("end", "{end}")
                        .queryParam("limit", "{limit}")
                        .build(Map.of(
                                "query", query,
                                "start", window.from().toString(),
                                "end", window.to().toString(),
                                "limit", limit)))
                .retrieve()
                .body(LokiResponse.class);

        return flatten(answer);
    }

    static TimeWindow resolveWindow(String from, String to, Integer windowMinutes) {
        Instant fin = (to != null && !to.isBlank()) ? Instant.parse(to) : Instant.now();
        Instant start = (from != null && !from.isBlank())
                ? Instant.parse(from)
                : fin.minus(Duration.ofMinutes(clamp(windowMinutes, DEFAULT_WINDOW_MINUTES, MAX_WINDOW_MINUTES)));
        return new TimeWindow(start, fin);
    }

    private static int clamp(Integer value, int defaultValue, int max) {
        return value == null ? defaultValue : Math.max(1, Math.min(value, max));
    }

    record TimeWindow(Instant from, Instant to) {}

    static String buildLogQl(String namespace, String pod, String container, String text) {
        StringBuilder selector = new StringBuilder("{namespace=\"").append(escape(namespace)).append('"');
        if (pod != null && !pod.isBlank()) {
            selector.append(",pod=\"").append(escape(pod)).append('"');
        }
        if (container != null && !container.isBlank()) {
            selector.append(",container=\"").append(escape(container)).append('"');
        }
        selector.append('}');
        if (text != null && !text.isBlank()) {
            selector.append(" |= \"").append(escape(text)).append('"');
        }
        return selector.toString();
    }

    static List<LogEntry> flatten(LokiResponse answer) {
        List<LogEntry> out = new ArrayList<>();
        if (answer == null || answer.data() == null || answer.data().result() == null) {
            return out;
        }
        for (LokiStream stream : answer.data().result()) {
            Map<String, String> labels = stream.stream() == null ? Map.of() : stream.stream();
            if (stream.values() == null) {
                continue;
            }
            for (List<String> value : stream.values()) {
                Instant ts = Instant.ofEpochSecond(0L, Long.parseLong(value.get(0)));
                out.add(new LogEntry(ts, labels.get("namespace"), labels.get("pod"), labels.get("container"), value.get(1)));
            }
        }
        out.sort(Comparator.comparing(LogEntry::timestamp));
        return out;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    record LokiResponse(String status, LokiData data) {}

    record LokiData(String resultType, List<LokiStream> result) {}

    record LokiStream(Map<String, String> stream, List<List<String>> values) {}
}
