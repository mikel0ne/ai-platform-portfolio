# AutoOps MCP & L1 Support Copilot

> Monorepo de portfolio con **dos proyectos independientes** desplegados en el mismo clúster local:
> **[AutoOps MCP](#autoops-mcp)** (servidor MCP para depurar Kubernetes + RAG) y
> **[L1 Support Copilot](#l1-support-copilot)** (agente de soporte L1 con LangGraph4j). Comparten clúster,
> Postgres, Weaviate y el pipeline de despliegue — ver [Infraestructura compartida](#infraestructura-compartida).

---

## AutoOps MCP

**AutoOps** es un **servidor MCP (Model Context Protocol)** que da a un asistente de IA —Claude Code, GitHub
Copilot o cualquier cliente MCP— acceso a los servicios de la empresa para **depurar errores de aplicaciones
en ejecución** sobre Kubernetes, y a la **documentación interna** mediante **RAG avanzado**.

No es un copiloto ni una aplicación de chat cerrada: el que razona es el cliente MCP; AutoOps solo **expone
herramientas y datos** de forma segura (lectura, nunca escritura sobre el clúster).

```
Claude Code / GitHub Copilot ──MCP (Streamable HTTP)──▶  autoops-mcp (Java · Spring Boot, en Kubernetes)
                                                    ├─ buscar_docs        → Weaviate  (RAG avanzado / CRAG)
                                                    ├─ diagnosticar_pod   → API K8s   (read-only)
                                                    ├─ logs_pod, eventos… → API K8s   (read-only)
                                                    └─ (chat de búsqueda)  ← front secundario sobre el mismo RAG
```

### Qué resuelve

Cuando una aplicación falla en el clúster, el desarrollador salta entre `kubectl describe`, eventos, logs y la
documentación interna para entender qué pasa. AutoOps pone todo eso **al alcance del asistente de IA que ya
usa**: el asistente pide el diagnóstico de un pod, sus logs, o el estándar interno aplicable, y razona la causa
con datos reales del clúster en vez de inventarlos.

### Capacidades (tools MCP)

| Tool | Para qué |
|---|---|
| `buscar_docs` | RAG avanzado sobre documentación interna (Weaviate: búsqueda híbrida + reranking + bucle correctivo CRAG). |
| `diagnosticar_pod` | Dossier estructurado de un pod: fase, condiciones, eventos, reinicios y cola de logs, en una sola llamada. |
| `estado_pods` / `listar_workloads` | Estado de pods y workloads de un namespace. |
| `describir_pod` / `eventos` / `logs_pod` | Piezas individuales para que el cliente componga su propio análisis. |
| `consultar_logs` | Histórico agregado cross-pod vía Loki (no solo el log en vivo del pod). |

Todo el acceso a Kubernetes es **estrictamente de solo lectura** (RBAC sin escritura ni acceso a Secrets).

Además del MCP, un **chat de búsqueda** (REST + UI mínima) reutiliza el mismo motor RAG para consultar la
documentación sin necesidad de un cliente MCP.

### Documentación

- **Arquitectura** (qué es y cómo se diseña el código): [`autoops/docs/arquitectura/`](autoops/docs/arquitectura/README.md)
- **Infraestructura** (despliegue en Kubernetes local): [`autoops/docs/infraestructura/`](autoops/docs/infraestructura/README.md)

### Stack

Java 17 + Spring Boot · **Spring AI MCP Server** (protocolo MCP) · **LangChain4j** + **Weaviate** (RAG) ·
**LangGraph4j** (subgrafo del bucle correctivo CRAG) · cliente Kubernetes (fabric8) · proveedor LLM/embeddings
abstraído (por defecto Gemini, capa gratuita). Despliegue en Kubernetes local (Rancher Desktop / k3s).

---

## L1 Support Copilot

**L1 Support Copilot** es un agente de soporte de nivel 1 **autónomo** para un e-commerce, construido con
**Spring Boot + LangChain4j + LangGraph4j + Gemini**. No es un bot de comandos: enruta la conversación
semánticamente, maneja ambigüedad de forma proactiva, mantiene estado conversacional entre turnos mediante un
grafo de estado con checkpointing, ejecuta acciones reales contra base de datos (consultar envíos, cancelar
pedidos, iniciar devoluciones) y escala a un humano creando tickets.

```text
Usuario:  ¿Dónde está mi pedido?
Agente:   Tienes dos pedidos recientes: un portátil (#123) y un ratón (#456).
          ¿Sobre cuál quieres consultar el estado?
Usuario:  El portátil
Agente:   Tu pedido #123 está EN REPARTO y llegará mañana con SEUR.
```

Ese diálogo ejercita lo que diferencia a este agente: detección de intención, detección de información
faltante, ejecución proactiva de tools (buscar los pedidos recientes en vez de pedir el ID a ciegas), un
**ciclo de clarificación** que sobrevive entre peticiones HTTP (checkpointer `MemorySaver` por `threadId`) y
resolución de referencias ("el portátil" → #123).

### Qué resuelve

Un agente de comandos clásico solo ejecuta lo que el usuario pide literalmente. Este copiloto va más allá:
clasifica la intención con el LLM pero deja que **código Java determinista decida** el flujo (aristas
condicionales testeables sin modelo) y las reglas de negocio (p. ej. `OrderStatus.isCancellable()` rechaza
cancelar un pedido ya enviado, no un prompt). El `userId` viene siempre de la sesión HTTP, nunca del texto del
usuario, para que un prompt malicioso no pueda cruzar datos entre usuarios.

### Capacidades (intents del router)

| Intent | Para qué |
|---|---|
| `TRACKING` | Consultar el estado/envío de un pedido (`resolve_order` si hace falta desambiguar). |
| `CANCEL` | Cancelar un pedido, con verificación de reglas de negocio + confirmación. |
| `RETURN` | Iniciar una devolución, con verificación + confirmación. |
| `SUPPORT` | Recopilar contexto y crear un ticket para un humano. |
| `TICKETS` | Consultar tickets ya abiertos por el usuario. |
| `FAQ` | Responder preguntas frecuentes con una base de conocimiento propia. |
| `CHATTY` / `OTHER` | Conversación social o fuera de alcance, sin tocar datos. |

Todo acceso a datos pasa por tools Java deterministas (`OrderTools`, `TicketTools`) sobre Spring Data JPA.

### Documentación

- **Arquitectura** (ADRs, grafo, modelo de datos, API): [`l1-copilot/docs/arquitectura/`](l1-copilot/docs/arquitectura/README.md)
- **Infraestructura** (despliegue en Kubernetes local): [`l1-copilot/docs/infraestructura/`](l1-copilot/docs/infraestructura/README.md)

### Stack

Java 17 + **Spring Boot** · **LangChain4j** (AiServices: `IntentClassifier`, `Verifier`, `Responder`) ·
**LangGraph4j** (grafo del agente con checkpointing) · Gemini (Google AI Studio) · PostgreSQL (H2 en local) ·
**Angular** (frontend de chat). Despliegue en Kubernetes local (Rancher Desktop / k3s).

---

## Infraestructura compartida

Ambos proyectos comparten clúster local (Rancher Desktop / k3s), un único PostgreSQL y un único Weaviate
(namespace `infra`), el stack de observabilidad (Loki + Grafana) y el pipeline de despliegue genérico
(`infra/scripts/deploy.sh --project <autoops|l1-copilot>`). Detalles completos en [`infra/INFRA.md`](infra/INFRA.md).
