package io.github.mikel0ne.autoops.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class DiagnosticsTools {

    @Tool(name = "ping",
          description = "Connectivity check for the AutoOps MCP server. Returns an echo with a timestamp.")
    public String ping(@ToolParam(description = "Text to echo back") String message) {
        return "pong: " + (message == null ? "" : message) + " @ " + OffsetDateTime.now();
    }
}
