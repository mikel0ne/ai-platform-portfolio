# 01 — Visión y alcance

## El problema

Cuando una aplicación falla en Kubernetes, depurarla obliga a saltar entre herramientas y contextos:

1. **Inspeccionar el clúster**: cruzar `kubectl describe`, eventos, logs y reinicios para entender por qué un
   pod no levanta o se reinicia.
2. **Buscar en la documentación interna** el estándar o procedimiento aplicable ("¿qué política de recursos
   aplica?", "¿cómo se configuran las probes según nuestra guía?").
3. **Correlacionar** ambas cosas para llegar a la causa raíz.

Hoy el desarrollador hace todo eso a mano, y el asistente de IA que ya usa (Claude Code, Copilot) no puede
ayudar porque **no tiene acceso** ni al clúster ni a la documentación: si le preguntas, alucina.

## La solución

Un **servidor MCP** que da a ese asistente acceso seguro y de **solo lectura** a:

- la **documentación interna**, vía una tool de **RAG avanzado** que valida la calidad de lo que recupera y se
  corrige sola si es floja (CRAG); y
- el **estado en runtime del clúster** (pods, eventos, logs, workloads), vía tools de inspección read-only.

El asistente combina esas tools y **razona la causa** del error con datos reales. AutoOps **no razona ni
redacta** para el usuario final (eso es del cliente MCP) y **nunca escribe** en el clúster.

### Casos de uso

| # | Caso de uso | Tools implicadas | Ejemplo (lo que el usuario pide a Claude Code) |
|---|---|---|---|
| CU-1 | Diagnóstico de un fallo en runtime | `diagnosticar_pod`, `logs_pod`, `eventos` | "¿Por qué está en CrashLoopBackOff el pod `payments-api`?" |
| CU-2 | Consulta de documentación interna | `buscar_docs` | "¿Cuál es nuestro estándar de probes para un microservicio Java?" |
| CU-3 | Diagnóstico fundamentado en el estándar | `diagnosticar_pod` + `buscar_docs` | "Este pod falla las probes; ¿cumple nuestra guía de readiness?" |
| CU-4 | Búsqueda de docs sin cliente MCP | chat de búsqueda (front secundario) | Preguntar a la documentación desde el navegador. |

### Flujo de usuario (CU-1, el caso central)

1. En Claude Code, el usuario pregunta por un pod que falla.
2. El cliente (Claude) decide llamar a la tool `diagnosticar_pod` de AutoOps.
3. AutoOps consulta la API de Kubernetes (read-only) y devuelve un **dossier estructurado**: fase,
   condiciones, eventos recientes, número de reinicios y cola de logs.
4. Claude razona sobre datos reales (p. ej. ve un `ErrImagePull` o un `OOMKilled`) y, si necesita el estándar
   interno, llama también a `buscar_docs`.
5. El usuario obtiene un diagnóstico fundamentado, citando eventos reales del clúster y la documentación.

## Qué queda FUERA del alcance

Decisiones explícitas para mantener el proyecto acotado y fiel a su propósito:

- **Escribir en el clúster** (`kubectl apply`, reinicios, escalados). Decisión de seguridad: AutoOps solo lee;
  cualquier acción la ejecuta el humano.
- **Generar o validar manifiestos YAML como función central.** No es depuración de runtime. A lo sumo podría
  añadirse en el futuro una tool secundaria `validar_manifiesto` (sin estado), pero **no** es prioridad.
- **Ser un copiloto/chat conversacional completo** con su propio LLM, login e historial. El razonamiento lo
  pone el cliente MCP. (El chat de búsqueda es un front mínimo, solo para el RAG de documentación.)
- **Multi-tenancy y gestión de usuarios.** En local no aplica.
- **Despliegue en nube.** El MVP corre en Kubernetes local; la arquitectura no lo impide.

## Criterios de éxito

1. Un cliente MCP real (Claude Code) **descubre y lista** las tools de AutoOps tras registrarlo.
2. `buscar_docs` devuelve fragmentos relevantes del corpus interno, y **mejora la respuesta** cuando la primera
   recuperación es floja (el bucle CRAG se activa de forma observable).
3. Con un pod roto a propósito (imagen inexistente), el asistente diagnostica la causa **citando el evento real**
   (`ErrImagePull`) obtenido por la tool.
4. El ServiceAccount de AutoOps **no puede** escribir en el clúster ni leer Secrets (verificable con
   `kubectl auth can-i`).
5. Cambiar de proveedor LLM/embeddings no toca el código de las tools (solo configuración + un bean).

## Glosario rápido

- **MCP (Model Context Protocol)**: protocolo estándar por el que un cliente de IA descubre y llama
  *herramientas* y *recursos* expuestos por un servidor.
- **Tool**: una función que AutoOps publica por MCP (p. ej. `diagnosticar_pod`) y que el cliente puede invocar.
- **RAG**: Retrieval-Augmented Generation; recuperar fragmentos de documentos para fundamentar una respuesta.
- **CRAG (Corrective RAG)**: variante en la que el sistema **califica** lo recuperado y, si no es bueno,
  **reformula la consulta y reintenta** antes de responder.
- **Read-only / RBAC**: permisos de Kubernetes estrictamente de lectura para el ServiceAccount del servidor.
