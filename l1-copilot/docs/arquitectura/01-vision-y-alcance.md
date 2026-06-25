# 01 — Visión y alcance

## Qué es el L1 Support Copilot

Un **agente de soporte de nivel 1 autónomo** para un e-commerce ficticio. No es un bot de comandos: es un agente que

- entiende **lenguaje natural** y enruta semánticamente la conversación (sin menús de botones),
- maneja **ambigüedad** (si falta información, la busca proactivamente o la pide),
- mantiene **estado conversacional** entre mensajes (memoria a corto plazo),
- ejecuta **acciones reales** contra una base de datos a través de herramientas Java deterministas,
- sabe cuándo **escalar a un humano** creando un ticket.

El objetivo del prototipo es demostrar dominio de: enrutamiento semántico, ciclos con LangGraph4j, tool-calling seguro desde Java y una arquitectura full-stack realista.

## Las habilidades (Skills)

### 1. Seguimiento de pedidos — informacional (`TRACKING`)

El usuario pregunta dónde está su paquete. El agente:

1. Detecta la intención `TRACKING`.
2. Comprueba en su estado si ya conoce el `orderId`.
3. Si **no** lo conoce, en vez de pedirlo a ciegas llama a la tool `getRecentOrders(userId)` y ofrece al usuario sus pedidos recientes para que elija (proactividad).
4. Cuando tiene el `orderId`, llama a `trackShipment(orderId)` y responde con estado + fecha estimada de entrega.

### 2. Cancelación / modificación — transaccional (`CANCEL`)

El usuario quiere cancelar un pedido. El agente:

1. Detecta la intención `CANCEL`.
2. Resuelve el `orderId` igual que en seguimiento (con el mismo subflujo reutilizable de desambiguación).
3. Valida **reglas de negocio en código Java** (no en el prompt): solo se puede cancelar si el pedido está en estado `CREATED` o `PAID`. Si ya está `SHIPPED`, la tool devuelve el motivo del rechazo (la vía es esperar y devolver); si ya está `DELIVERED`, el agente reconduce hacia una **devolución**.
4. Pide **confirmación explícita** antes de ejecutar la cancelación (acción destructiva → human-in-the-loop).
5. Ejecuta `cancelOrder(orderId)` y confirma el resultado.

### 3. Devolución — transaccional (`RETURN`)

El usuario quiere **devolver un pedido que ya ha recibido**. Es el espejo de la cancelación para la otra punta del ciclo de vida. El agente:

1. Detecta la intención `RETURN` (distinta de CANCEL: "cancelar" un pedido ya entregado se trata como devolución; un producto recibido **defectuoso** es `SUPPORT`, no una devolución normal).
2. Resuelve el `orderId` con el mismo subflujo de desambiguación.
3. Valida en **código Java** que el pedido admite devolución (`isReturnable()` ⇔ estado `DELIVERED`).
4. Pide **confirmación explícita** (botón). Una devolución **nunca** se gestiona abriendo una incidencia: tiene su propia vía.
5. Ejecuta `requestReturn(orderId)`, pone el pedido en estado `RETURN_REQUESTED` y entrega un enlace a la **etiqueta de devolución** (dirección del almacén, referencia `RET-####` y código de barras).

### 4. Soporte técnico / incidencias — escalado (`SUPPORT`)

El usuario reporta un problema (producto roto, no funciona…). El agente:

1. Detecta la intención `SUPPORT`.
2. Recopila en varios turnos la información mínima del ticket: pedido afectado (opcional), descripción del problema.
3. Crea un ticket en BD con `createTicket(userId, orderId?, descripcion)` y devuelve el número de ticket y expectativas ("un agente humano te contactará").

### Intención residual (`OTHER` / smalltalk)

Saludos, preguntas fuera de dominio, etc. El agente responde cortésmente, explica qué sabe hacer y no inventa capacidades. Es el camino por defecto del router.

## Escenario de referencia (el que debe funcionar de punta a punta)

> **Usuario:** "¿Dónde está mi pedido?"
> **Agente** *(router → TRACKING; falta orderId; tool `getRecentOrders`)*: "Veo que tienes dos pedidos recientes: un portátil (Pedido #123) y un ratón (Pedido #456). ¿Sobre cuál quieres consultar el estado?"
> **Usuario:** "El portátil"
> **Agente** *(mapea "portátil" → #123; tool `trackShipment("123")`)*: "Tu pedido #123 (portátil) está EN REPARTO y llegará mañana, 12 de junio."

Este escenario ejercita: enrutamiento, detección de información faltante, ejecución proactiva de tools, ciclo de clarificación (el grafo termina el turno con una pregunta y **se reanuda** en el siguiente mensaje gracias al checkpointer) y resolución de referencias ("el portátil" → #123) usando el contexto del estado.

## Requisitos no funcionales del prototipo

| Requisito | Decisión |
|---|---|
| Seguridad del dato | El `userId` viaja del frontend al backend (token simulado) y se inyecta en las tools desde código; el LLM nunca decide de qué usuario se consultan datos |
| Coste / cuota | Gemini `gemini-2.5-flash-lite` (capa gratuita de AI Studio); el router y los nodos usan prompts cortos |
| Persistencia | H2 en `dev` para arrancar sin nada instalado; PostgreSQL en despliegue Kubernetes |
| Memoria conversacional | `MemorySaver` (en memoria) en el prototipo; el diseño deja el `BaseCheckpointSaver` como punto de extensión para persistirlo |
| Observabilidad | Logs estructurados por nodo del grafo (`---ROUTER---`, `---TRACKING---`…), Actuator health |
| Idioma | El agente conversa en español (prompt de sistema), aunque entiende entradas en otros idiomas |

## Fuera de alcance (explícitamente)

- Autenticación real (OAuth/JWT): se simula un usuario logueado en Angular.
- Streaming token a token de las respuestas (posible mejora; la API se diseña para poder añadir SSE después).
- RAG / búsqueda documental.
- Multi-tenancy, i18n del frontend, alta disponibilidad.
- Persistencia del checkpoint del grafo entre reinicios del backend.

## Criterios de éxito del prototipo

1. Los 3 flujos completos funcionan vía chat en Angular contra datos reales en BD.
2. El escenario de referencia (ambigüedad + ciclo) funciona sin hacks: el estado se reanuda por `threadId`.
3. Una cancelación de pedido `SHIPPED` es **rechazada por código Java**, no por el prompt.
4. El grafo es visualizable (LangGraph4j puede exportar PlantUML/Mermaid de la topología) y el diagrama está en el README del repo.
