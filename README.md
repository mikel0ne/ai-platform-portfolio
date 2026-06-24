# AutoOps MCP

**AutoOps** es un **servidor MCP (Model Context Protocol)** que da a un asistente de IA —Claude Code, GitHub
Copilot o cualquier cliente MCP— acceso a los servicios de la empresa para **depurar errores de aplicaciones
en ejecución** sobre Kubernetes, y a la **documentación interna** mediante **RAG avanzado**.

No es un copiloto ni una aplicación de chat cerrada: el que razona es el cliente MCP; AutoOps solo **expone
herramientas y datos** de forma segura (lectura, nunca escritura sobre el clúster).

```
Claude Code / GitHub Copilot ──MCP (HTTP/SSE)──▶  autoops-mcp (Java · Spring Boot, en Kubernetes)
                                                    ├─ buscar_docs        → Weaviate  (RAG avanzado / CRAG)
                                                    ├─ diagnosticar_pod   → API K8s   (read-only)
                                                    ├─ logs_pod, eventos… → API K8s   (read-only)
                                                    └─ (chat de búsqueda)  ← front secundario sobre el mismo RAG
```

## Qué resuelve

Cuando una aplicación falla en el clúster, el desarrollador salta entre `kubectl describe`, eventos, logs y la
documentación interna para entender qué pasa. AutoOps pone todo eso **al alcance del asistente de IA que ya
usa**: el asistente pide el diagnóstico de un pod, sus logs, o el estándar interno aplicable, y razona la causa
con datos reales del clúster en vez de inventarlos.

## Capacidades (tools MCP)

| Tool | Para qué |
|---|---|
| `buscar_docs` | RAG avanzado sobre documentación interna (Weaviate: búsqueda híbrida + reranking + bucle correctivo CRAG). |
| `diagnosticar_pod` | Dossier estructurado de un pod: fase, condiciones, eventos, reinicios y cola de logs, en una sola llamada. |
| `estado_pods` / `listar_workloads` | Estado de pods y workloads de un namespace. |
| `describir_pod` / `eventos` / `logs_pod` | Piezas individuales para que el cliente componga su propio análisis. |

Todo el acceso a Kubernetes es **estrictamente de solo lectura** (RBAC sin escritura ni acceso a Secrets).

Además del MCP, un **chat de búsqueda** (REST + UI mínima) reutiliza el mismo motor RAG para consultar la
documentación sin necesidad de un cliente MCP.

## Documentación

- **Arquitectura** (qué es y cómo se diseña el código): [`docs/arquitectura/`](docs/arquitectura/README.md)
- **Infraestructura** (despliegue en Kubernetes local): [`docs/infraestructura/`](docs/infraestructura/README.md)

## Stack

Java 17 + Spring Boot · **Spring AI MCP Server** (protocolo MCP) · **LangChain4j** + **Weaviate** (RAG) ·
**LangGraph4j** (subgrafo del bucle correctivo CRAG) · cliente Kubernetes (fabric8) · proveedor LLM/embeddings
abstraído (por defecto Gemini, capa gratuita). Despliegue en Kubernetes local (Docker Desktop / Minikube).
