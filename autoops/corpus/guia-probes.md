# Guía interna de probes (liveness, readiness, startup)

Estándar obligatorio para todos los microservicios desplegados en el clúster. El objetivo es que
Kubernetes pueda distinguir entre "el proceso vive", "el proceso puede recibir tráfico" y "el
proceso todavía está arrancando".

## Readiness probe

- Toda carga de trabajo que reciba tráfico **debe** declarar una readiness probe.
- La readiness debe comprobar las dependencias críticas (base de datos, broker, servicios aguas
  abajo de los que dependa la petición). Si una dependencia crítica no está disponible, el pod
  debe reportarse **no Ready** para salir del balanceo.
- Endpoint recomendado para servicios Spring Boot: `GET /actuator/health/readiness`.
- Valores por defecto: `periodSeconds: 10`, `failureThreshold: 3`, `timeoutSeconds: 2`.
- La readiness **no** debe reiniciar el contenedor: solo lo saca del Service hasta que vuelva a
  estar listo.

## Liveness probe

- La liveness detecta procesos colgados (deadlock, bucle infinito) que ya no se recuperan solos.
- Debe ser **barata** y no comprobar dependencias externas: si la BD está caída, no queremos que
  Kubernetes reinicie todos los pods en cascada.
- Endpoint recomendado: `GET /actuator/health/liveness`.
- Valores por defecto: `periodSeconds: 10`, `failureThreshold: 3`. Un fallo sostenido provoca el
  reinicio del contenedor.

## Startup probe

- Obligatoria para servicios con arranque lento (JVM grande, carga de cachés, migraciones).
- Mientras la startup no pasa, se **desactivan** liveness y readiness, evitando que un arranque
  lento se confunda con un proceso muerto y entre en CrashLoopBackOff.
- Configurar `failureThreshold` y `periodSeconds` para cubrir el peor tiempo de arranque esperado
  (p. ej. `failureThreshold: 30`, `periodSeconds: 5` → hasta 150s de margen).

## Errores frecuentes

- **CrashLoopBackOff al arrancar**: casi siempre falta una startup probe y la liveness reinicia el
  pod antes de que termine de arrancar.
- **Pods Ready que devuelven 503**: la readiness no comprueba las dependencias críticas.
- **Reinicios en cascada cuando cae la BD**: la liveness comprueba la base de datos; no debe.
