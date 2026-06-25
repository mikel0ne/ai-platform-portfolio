# Observabilidad: logging, métricas y trazas

Estándar de observabilidad para poder diagnosticar incidentes en runtime sin acceso interactivo a
los pods.

## Logging

- Logs a **stdout/stderr** en formato **JSON estructurado** (un objeto por línea). Kubernetes y el
  agregador de logs se encargan de la recolección; nada de escribir a ficheros dentro del pod.
- Campos mínimos por evento: `timestamp`, `level`, `logger`, `message`, `traceId`, `service`.
- Niveles: `INFO` para el flujo normal, `WARN` para situaciones recuperables, `ERROR` para fallos
  que requieren atención. `DEBUG` solo se activa temporalmente para diagnóstico.
- **Nunca** registrar datos sensibles (credenciales, API keys, PII, payloads completos) en niveles
  `INFO` o superiores.

## Métricas

- Exponer métricas Prometheus en `/actuator/prometheus` (Micrometer).
- Métricas mínimas: latencia por endpoint (histograma), tasa de errores, saturación de pools
  (conexiones, hilos). Para AutoOps, además: histograma de iteraciones del bucle CRAG y latencia
  por tool.
- Definir alertas sobre síntomas (latencia p99, tasa de error), no sobre causas.

## Trazas

- Propagar el contexto de traza (W3C `traceparent`) en todas las llamadas salientes.
- Cada petición entrante genera o continúa una traza; el `traceId` se incluye en todos los logs de
  esa petición para poder correlacionar logs y trazas.

## Correlación en un incidente

1. Partir del síntoma (alerta de latencia o error).
2. Localizar el `traceId` afectado y filtrar logs por él.
3. Si el pod ha reiniciado, consultar los logs del arranque anterior (`previous`) para ver el motivo
   del crash.
