package io.github.mikel0ne.autoops.observability;

import io.github.mikel0ne.autoops.observability.model.LogEntry;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Cliente de Loki para la tool {@code consultar_logs} (doc 05 §1, roadmap F3/F5): construye la query LogQL
 * a partir de filtros estructurados, resuelve la ventana temporal y aplana la respuesta anidada de
 * {@code /loki/api/v1/query_range} a {@link LogEntry}. La construcción de query/ventana y el aplanado son
 * {@code static} y puros para poder testearlos sin un Loki real (mismo criterio que
 * {@code K8sInspectionService}).
 */
@Service
public class LokiGateway {

    private final RestClient client;

    public LokiGateway(RestClient lokiClient) {
        this.client = lokiClient;
    }

    public List<LogEntry> queryRange(String namespace, String pod, String contenedor, String texto,
                                      String desde, String hasta, Integer ventanaMinutos, Integer lineas) {
        Ventana ventana = resolveVentana(desde, hasta, ventanaMinutos);
        String query = buildLogQl(namespace, pod, contenedor, texto);
        int limit = lineas == null ? 200 : lineas;

        LokiResponse respuesta = client.get()
                .uri(uri -> uri.path("/loki/api/v1/query_range")
                        .queryParam("query", query)
                        .queryParam("start", ventana.desde().toString())
                        .queryParam("end", ventana.hasta().toString())
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .body(LokiResponse.class);

        return flatten(respuesta);
    }

    /**
     * Resuelve la ventana de tiempo a consultar. Sin {@code desde}/{@code hasta}, son los últimos
     * {@code ventanaMinutos} (por defecto 15) hasta ahora. Con ambos en ISO-8601 (admite milisegundos),
     * permite acotar a un instante muy concreto — p. ej. correlacionar con un timestamp ya visto en otro log.
     */
    static Ventana resolveVentana(String desde, String hasta, Integer ventanaMinutos) {
        Instant fin = (hasta != null && !hasta.isBlank()) ? Instant.parse(hasta) : Instant.now();
        Instant inicio = (desde != null && !desde.isBlank())
                ? Instant.parse(desde)
                : fin.minus(Duration.ofMinutes(ventanaMinutos == null ? 15 : ventanaMinutos));
        return new Ventana(inicio, fin);
    }

    record Ventana(Instant desde, Instant hasta) {}

    static String buildLogQl(String namespace, String pod, String contenedor, String texto) {
        StringBuilder selector = new StringBuilder("{namespace=\"").append(escape(namespace)).append('"');
        if (pod != null && !pod.isBlank()) {
            selector.append(",pod=\"").append(escape(pod)).append('"');
        }
        if (contenedor != null && !contenedor.isBlank()) {
            selector.append(",container=\"").append(escape(contenedor)).append('"');
        }
        selector.append('}');
        if (texto != null && !texto.isBlank()) {
            selector.append(" |= \"").append(escape(texto)).append('"');
        }
        return selector.toString();
    }

    static List<LogEntry> flatten(LokiResponse respuesta) {
        List<LogEntry> out = new ArrayList<>();
        if (respuesta == null || respuesta.data() == null || respuesta.data().result() == null) {
            return out;
        }
        for (LokiStream stream : respuesta.data().result()) {
            Map<String, String> labels = stream.stream() == null ? Map.of() : stream.stream();
            if (stream.values() == null) {
                continue;
            }
            for (List<String> valor : stream.values()) {
                Instant ts = Instant.ofEpochSecond(0L, Long.parseLong(valor.get(0)));
                out.add(new LogEntry(ts, labels.get("namespace"), labels.get("pod"), labels.get("container"), valor.get(1)));
            }
        }
        out.sort(Comparator.comparing(LogEntry::timestamp));
        return out;
    }

    private static String escape(String valor) {
        return valor.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    record LokiResponse(String status, LokiData data) {}

    record LokiData(String resultType, List<LokiStream> result) {}

    record LokiStream(Map<String, String> stream, List<List<String>> values) {}
}
