package io.github.mikel0ne.autoops.observability.model;

import java.time.Instant;

/**
 * Línea de log agregada desde Loki, aplanada desde el JSON anidado de {@code /loki/api/v1/query_range}
 * (doc 05 §1).
 *
 * @param timestamp  instante de la línea
 * @param namespace  namespace del pod que la emitió
 * @param pod        nombre del pod
 * @param contenedor contenedor dentro del pod
 * @param linea      contenido de la línea de log
 */
public record LogEntry(Instant timestamp, String namespace, String pod, String contenedor, String linea) {}
