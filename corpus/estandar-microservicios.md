# Estándar de microservicios Java

Convenciones obligatorias para crear y operar microservicios Java (Spring Boot) en la organización.

## Estructura y configuración

- Un microservicio = un repositorio = una responsabilidad de negocio. Nada de servicios "cajón
  de sastre".
- Configuración por entorno mediante variables de entorno y perfiles de Spring; **nunca** secretos
  en el repositorio ni en la imagen. Los secretos se inyectan desde Secrets de Kubernetes.
- El artefacto se empaqueta en una imagen OCI multi-stage; la imagen no contiene credenciales.

## Salud y ciclo de vida

- Exponer Actuator con los endpoints de salud separados: `/actuator/health/liveness` y
  `/actuator/health/readiness` (ver la guía de probes).
- Implementar apagado ordenado (`server.shutdown=graceful`) para drenar peticiones en vuelo antes
  de terminar; declarar un `preStop` y un `terminationGracePeriodSeconds` acorde.

## Contrato de API

- API REST versionada en la ruta (`/v1/...`). Los cambios deben ser **aditivos**; renombrar o
  eliminar campos es un cambio rompedor que exige nueva versión.
- Documentar la API con OpenAPI; el contrato es la fuente de verdad para los consumidores.
- Errores en formato problem+json (RFC 7807); nunca se filtran stacktraces ni detalles internos.

## Resiliencia

- Toda llamada saliente lleva **timeout** explícito; sin timeout es un defecto.
- Aplicar reintentos con backoff solo a operaciones idempotentes.
- Usar cortacircuitos (circuit breaker) para aislar dependencias inestables y degradar con
  elegancia en lugar de propagar fallos.

## Despliegue

- Cada despliegue usa un **tag de imagen único** (no `latest`): con `imagePullPolicy: IfNotPresent`
  y un tag fijo reutilizado, el pod nunca se actualizaría.
- Declarar requests y limits según la política de recursos.
