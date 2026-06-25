package io.github.mikel0ne.autoops.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Tools MCP de AutoOps (catálogo completo en docs/arquitectura/03-tools-mcp.md).
 *
 * <p>F0 (esqueleto): solo {@code ping}, para verificar que un cliente MCP real (Claude Code/Copilot)
 * descubre e invoca las tools. En F2 se añaden aquí (o en {@code KubernetesTools}) las tools de
 * diagnóstico de runtime: {@code diagnosticar_pod}, {@code estado_pods}, {@code logs_pod}…
 */
@Component
public class DiagnosticsTools {

    @Tool(name = "ping",
          description = "Comprobación de conectividad del servidor MCP AutoOps. Devuelve un eco con marca de tiempo.")
    public String ping(@ToolParam(description = "Texto a devolver en el eco") String mensaje) {
        return "pong: " + (mensaje == null ? "" : mensaje) + " @ " + OffsetDateTime.now();
    }
}
