# 01 — Visión y alcance

## El problema

Los equipos que operan sobre Kubernetes pierden tiempo en tres tareas repetitivas:

1. **Buscar en documentación interna** estándares de arquitectura ("¿cuál es nuestra convención de probes?", "¿qué política de recursos aplica a un microservicio Java?").
2. **Diagnosticar workloads**: cruzar `kubectl describe`, eventos y logs para entender por qué un pod no levanta.
3. **Escribir YAML de Kubernetes** correcto y conforme a las políticas del equipo, una tarea mecánica y propensa a errores.

## La solución

Un copiloto conversacional con **tres rutas de trabajo**, decididas automáticamente por un nodo router, y con una propiedad diferencial: cuando genera YAML, **lo valida y se auto-corrige en un ciclo** antes de entregarlo. La IA nunca entrega un manifiesto que no haya pasado validación sintáctica y de políticas, y **nunca aplica cambios al clúster** (solo lee).

### Casos de uso del MVP

| # | Caso de uso | Ruta del grafo | Ejemplo |
|---|---|---|---|
| CU-1 | Consulta de documentación | `docs` (RAG) | "¿Cuál es el estándar de microservicios del equipo?" |
| CU-2 | Diagnóstico del clúster | `diagnose` (tools) | "¿Por qué está fallando el pod payments-api en staging?" |
| CU-3 | Generación de manifiestos | `generate` (ciclo generar→validar→corregir) | "Genera un Deployment para un servicio Java en el puerto 8080 con 2 réplicas" |
| CU-4 | Historial de conversaciones | — (gateway) | Retomar una conversación anterior desde el dashboard |

### Flujo de usuario (CU-3, el diferencial)

1. El usuario pide un manifiesto en el chat.
2. El router clasifica la petición como `generate`.
3. El nodo generador redacta el YAML.
4. El nodo validador comprueba: sintaxis YAML, esquema del recurso K8s y políticas internas (recursos obligatorios, probes, no `latest`, no privilegios…).
5. Si falla, el error vuelve al generador con el contexto del intento anterior (**self-correction**); máximo N iteraciones.
6. El usuario recibe el YAML final + el veredicto de validación + cuántas iteraciones costó (transparencia).

## Qué queda FUERA del MVP

Explícitamente descartado para mantener el proyecto acotado (candidatos a iteraciones futuras):

- **Aplicar cambios al clúster** (`kubectl apply` desde el agente). Decisión de seguridad, no técnica: el humano siempre aplica.
- Multi-tenancy y gestión de usuarios real (el MVP usa usuarios estáticos con JWT).
- Streaming token a token en la UI (el grafo emite por nodos; se puede añadir SSE después).
- Observabilidad avanzada de LLM (LangFuse/OTel); el MVP loguea el tránsito por nodos.
- Despliegue en nube (AKS); el MVP es Kubernetes local. La arquitectura no lo impide: es la misma imagen y los mismos manifiestos.
- Fine-tuning o modelos locales.

## Criterios de éxito del MVP

1. Las tres rutas funcionan end-to-end desde la UI Angular desplegada en Kubernetes local.
2. Un YAML inválido a la primera se corrige solo en ≤ 3 iteraciones en los casos de prueba.
3. Cambiar de proveedor LLM no toca código de negocio (solo configuración + un bean).
4. El historial sobrevive a reinicios de pods (persistencia en PostgreSQL).
5. El agente puede explicar por qué falla un pod roto de prueba (`CrashLoopBackOff` provocado) citando eventos reales del clúster.

## Glosario rápido

- **Grafo / StateGraph**: flujo de ejecución del agente, definido con LangGraph4j (nodos + edges + estado compartido).
- **Ruta**: una de las tres trayectorias del grafo (`docs`, `diagnose`, `generate`).
- **Self-correction**: ciclo generar→validar→regenerar con el error como feedback.
- **RAG**: Retrieval-Augmented Generation; respuestas fundamentadas en documentos indexados (pgvector).
