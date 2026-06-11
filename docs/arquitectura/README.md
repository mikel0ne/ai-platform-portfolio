# Arquitectura — L1 Support Copilot

Documentación exhaustiva de diseño y código del **Agente de Soporte de Nivel 1 Autónomo** (L1 Support Copilot): un agente conversacional construido con **Spring Boot + LangChain4j + LangGraph4j + Gemini (Google AI Studio)** y frontend **Angular**, capaz de enrutar de forma autónoma entre tres habilidades (seguimiento de pedidos, cancelación y soporte técnico), manejar ambigüedad y mantener estado conversacional mediante ciclos de grafo.

> Esta documentación está pensada para ejecutarse **en conversaciones/sesiones separadas**: cada documento es autocontenido y el [plan de implementación](09-plan-implementacion.md) trocea el trabajo en fases independientes.

## Índice

| Doc | Contenido |
|---|---|
| [01 — Visión y alcance](01-vision-y-alcance.md) | Qué es el L1 Support Copilot, habilidades, escenarios de uso, qué queda fuera |
| [02 — Arquitectura del sistema](02-arquitectura-sistema.md) | Componentes, diagramas C4/Mermaid, flujo de una petición extremo a extremo |
| [03 — Modelo de datos](03-modelo-datos.md) | Tablas, entidades JPA, reglas de negocio, datos de prueba (seed) |
| [04 — El grafo LangGraph4j](04-grafo-langgraph.md) | `AgentState`, nodos, aristas condicionales, ciclos, checkpointing y memoria conversacional |
| [05 — Tools Java](05-tools-java.md) | Servicios `@Tool`, contrato de cada herramienta, seguridad del dato |
| [06 — API REST](06-api-rest.md) | Endpoints, DTOs, gestión de `conversationId`, errores |
| [07 — Organización del código](07-organizacion-codigo.md) | Paquetes, dependencias Maven a añadir, convenciones, perfiles |
| [08 — Frontend Angular](08-frontend-angular.md) | UI de chat, login simulado, comunicación con el backend |
| [09 — Plan de implementación](09-plan-implementacion.md) | Fases, tareas detalladas y criterios de aceptación |

## Reglas de oro del proyecto

1. **Flujos cíclicos = LangGraph4j.** El agente se modela con `StateGraph`, `Node` y `Edge` de LangGraph4j (ver `llms-lgraph.txt`). No se simulan ciclos con cadenas lineales de LangChain4j.
2. **Servicios lineales y wiring Spring = LangChain4j.** La integración con Gemini, los `@Tool` y los servicios REST siguen los patrones de `llms-lc4j.txt`.
3. **La IA orquesta, el código Java ejecuta.** Todo acceso a datos pasa por métodos `@Tool` deterministas; el `userId` se inyecta desde la sesión, nunca desde el LLM (anti prompt-injection).
4. **El estado vive en el grafo.** La memoria a corto plazo (intención detectada, `orderId` pendiente, historial) se guarda en el `AgentState` con checkpointer y `threadId` = id de conversación.

## Stack resumido

- **Backend:** Java 17, Spring Boot 3.2.x, LangChain4j (Gemini / Google AI Studio), LangGraph4j, Spring Data JPA.
- **Base de datos:** H2 en memoria (perfil `dev`), PostgreSQL (perfil `prod`/Kubernetes).
- **Frontend:** Angular 17+ (standalone components), interfaz de chat con usuario "logueado" simulado.
- **Infraestructura:** ver rama `docs/infraestructura-k8s` (Kubernetes local con manifiestos completos).
