package io.github.mikel0ne.autoops.tools;

import io.github.mikel0ne.autoops.observability.LokiGateway;
import io.github.mikel0ne.autoops.observability.model.LogEntry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ObservabilityTools {

    private final LokiGateway loki;

    public ObservabilityTools(LokiGateway loki) {
        this.loki = loki;
    }

    @Tool(name = "query_logs",
          description = "Aggregated logs from Loki (history across several pods/namespaces), unlike pod_logs which only "
                  + "sees the current pod. Without 'from'/'to' it returns the last 'windowMinutes' (default 15) up to now. "
                  + "To narrow down to a precise instant (e.g. to correlate with a timestamp seen in another log), pass "
                  + "'from'/'to' as ISO-8601 with milliseconds, e.g. 2026-06-26T14:32:07.123Z.")
    public List<LogEntry> queryLogs(
            @ToolParam(description = "Namespace to query") String namespace,
            @ToolParam(required = false, description = "Pod name (optional filter)") String pod,
            @ToolParam(required = false, description = "Container (optional filter)") String container,
            @ToolParam(required = false, description = "Text the line must contain (optional)") String text,
            @ToolParam(required = false, description = "Start instant, ISO-8601, milliseconds allowed (optional)") String from,
            @ToolParam(required = false, description = "End instant, ISO-8601, milliseconds allowed (optional, default now)") String to,
            @ToolParam(required = false, description = "Without 'from': minutes back from 'to' (default 15)") Integer windowMinutes,
            @ToolParam(required = false, description = "Maximum number of lines (default 200)") Integer lines) {
        return loki.queryRange(namespace, pod, container, text, from, to, windowMinutes, lines);
    }
}
