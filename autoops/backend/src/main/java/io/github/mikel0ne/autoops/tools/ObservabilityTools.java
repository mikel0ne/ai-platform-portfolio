package io.github.mikel0ne.autoops.tools;

import io.github.mikel0ne.autoops.observability.LokiGateway;
import io.github.mikel0ne.autoops.observability.model.LogEntry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tool MCP de logs agregados (Loki), <b>de solo lectura</b> — complementa a {@code logs_pod} (que solo ve
 * el pod actual) con histórico correlacionable entre varios pods/namespaces en una ventana de tiempo
 * (doc 05 §1, roadmap F3/F5).
 */
@Component
public class ObservabilityTools {

    private final LokiGateway loki;

    public ObservabilityTools(LokiGateway loki) {
        this.loki = loki;
    }

    @Tool(name = "consultar_logs",
          description = "Logs agregados desde Loki (histórico, varios pods/namespaces a la vez), a diferencia de "
                  + "logs_pod que solo ve el pod actual. Sin 'desde'/'hasta', usa los últimos 'ventanaMinutos' "
                  + "(por defecto 15) hasta ahora. Para acotar a un instante muy concreto (p. ej. correlacionar "
                  + "con un timestamp ya visto en otro log), pasa 'desde'/'hasta' en ISO-8601 con milisegundos, "
                  + "p. ej. 2026-06-26T14:32:07.123Z.")
    public List<LogEntry> consultarLogs(
            @ToolParam(description = "Namespace a consultar") String namespace,
            @ToolParam(required = false, description = "Nombre del pod (opcional, filtra)") String pod,
            @ToolParam(required = false, description = "Contenedor (opcional, filtra)") String contenedor,
            @ToolParam(required = false, description = "Texto que debe contener la línea (opcional)") String texto,
            @ToolParam(required = false, description = "Instante inicial ISO-8601, admite milisegundos (opcional)") String desde,
            @ToolParam(required = false, description = "Instante final ISO-8601, admite milisegundos (opcional, por defecto ahora)") String hasta,
            @ToolParam(required = false, description = "Si no se indica 'desde': minutos hacia atrás desde 'hasta' (por defecto 15)") Integer ventanaMinutos,
            @ToolParam(required = false, description = "Máximo de líneas a devolver (por defecto 200)") Integer lineas) {
        return loki.queryRange(namespace, pod, contenedor, texto, desde, hasta, ventanaMinutos, lineas);
    }
}
