# Política de recursos (requests y limits)

Norma de capacidad para todas las cargas de trabajo del clúster. Busca un equilibrio entre
estabilidad (que un servicio no ahogue a sus vecinos) y aprovechamiento (no reservar de más).

## Requests y limits de memoria

- Todo contenedor **debe** declarar `requests.memory` y `limits.memory`.
- Para la JVM, `requests.memory` = `limits.memory` (memoria garantizada): evita que el OOM killer
  mate el pod por sobrecompromiso del nodo. Dimensionar el heap con `-XX:MaxRAMPercentage=75.0`.
- Un contenedor que supere su `limits.memory` es terminado con **OOMKilled**; aparecerá como
  motivo de reinicio en el estado del contenedor.

## Requests y limits de CPU

- Declarar siempre `requests.cpu` (lo que el scheduler reserva). Es la base del autoescalado.
- **No** poner `limits.cpu` en servicios sensibles a latencia: el CPU throttling introduce pausas
  difíciles de diagnosticar. Para batch o tareas no críticas, sí se permite limitar CPU.

## Clases de calidad de servicio (QoS)

- **Guaranteed**: requests = limits en CPU y memoria. Reservado para servicios críticos.
- **Burstable**: requests < limits. El caso por defecto de la mayoría de servicios.
- **BestEffort**: sin requests ni limits. **Prohibido** en producción: son los primeros en morir
  bajo presión de recursos del nodo.

## Valores de referencia por tipo de servicio

- API Java estándar: `requests` 512Mi / 250m CPU, `limits` 512Mi de memoria (sin límite de CPU).
- Worker de procesamiento: `requests` 1Gi / 500m, `limits` 1Gi de memoria.
- Job batch: puede usar `limits.cpu` para no monopolizar el nodo.

## Síntomas y diagnóstico

- **Pod en Pending con FailedScheduling / Insufficient memory**: los requests no caben en ningún
  nodo; reducir requests o ampliar el clúster.
- **Reinicios con OOMKilled**: subir `limits.memory` o revisar fugas/heap mal dimensionado.
- **Latencia con picos inexplicables**: probable CPU throttling por un `limits.cpu` demasiado bajo.
