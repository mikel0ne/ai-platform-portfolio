# 07 — Organización del código

## Punto de partida

El repo actual (rama `google-ai-studio`) ya aporta:

- Spring Boot 3.2.x + Java 17 (`pom.xml`).
- Integración Gemini/Google AI Studio: bean `ChatLanguageModel` en `config/Config.java`, propiedades `langchain4j.gemini.*`.
- Caché de respuestas en fichero (`cache/FileCacheManager`), Swagger, Actuator.

El L1 Copilot **se construye encima**, en paquetes nuevos, sin romper los endpoints existentes.

## Árbol de paquetes objetivo

```
src/main/java/io/github/mikel0ne/l1copilot/
├── Application.java
├── config/
│   ├── Config.java                  # (existente) bean ChatLanguageModel Gemini
│   ├── LangChain4jProperties.java   # (existente)
│   ├── OpenApiConfig.java           # (existente)
│   └── AgentConfig.java             # NUEVO: CompiledGraph + checkpointer
├── controller/
│   ├── LlmController.java           # (existente)
│   ├── CacheController.java         # (existente)
│   └── ChatController.java          # NUEVO: /api/copilot/**
├── service/
│   ├── LlmService.java              # (existente)
│   └── ConversationService.java     # NUEVO: invoca el grafo por threadId
├── agent/                           # NUEVO: todo el agente LangGraph4j
│   ├── SupportAgentGraph.java       # builder del StateGraph + aristas condicionales
│   ├── SupportState.java            # AgentState + SCHEMA
│   ├── Intent.java
│   ├── Awaiting.java
│   ├── node/
│   │   ├── RouterNode.java
│   │   ├── ResolveOrderNode.java
│   │   ├── TrackingNode.java
│   │   ├── CancelCheckNode.java
│   │   ├── CancelExecNode.java
│   │   ├── SupportNode.java
│   │   ├── FallbackNode.java
│   │   └── RespondNode.java
│   └── llm/
│       ├── IntentClassifier.java    # AiService: clasificación estructurada
│       └── Responder.java           # AiService: redacción de la respuesta
├── tools/                           # NUEVO
│   ├── OrderTools.java
│   └── TicketTools.java
├── domain/                          # NUEVO
│   ├── Order.java / OrderStatus.java
│   ├── Ticket.java / TicketStatus.java
│   ├── User.java
│   └── repository/
│       ├── OrderRepository.java
│       ├── TicketRepository.java
│       └── UserRepository.java
├── dto/                             # NUEVO
│   ├── ChatRequest.java / ChatResponse.java / UserDto.java
│   └── tools/ (OrderSummary, ShipmentStatus, CancellationResult, TicketCreated)
└── cache/                           # (existente)
```

Criterios:

- **`agent/` no conoce HTTP** (no importa nada de `controller/`); `controller/` no conoce LangGraph4j. El puente es `ConversationService`.
- **`tools/` no conoce el grafo**: reciben primitivas y devuelven records de `dto/tools`. Reutilizables desde un futuro `AiServices` con function-calling.
- Los **AiServices** (interfaces con `@SystemMessage`) viven junto al agente (`agent/llm/`) porque sus prompts son parte de la lógica del grafo.

## Dependencias Maven a añadir

```xml
<properties>
    <java.version>17</java.version>
    <langchain4j.version>0.36.2</langchain4j.version>
    <langgraph4j.version><!-- ver nota de compatibilidad --></langgraph4j.version>
</properties>

<dependencies>
    <!-- LangGraph4j: el motor del grafo de estado -->
    <dependency>
        <groupId>org.bsc.langgraph4j</groupId>
        <artifactId>langgraph4j-core</artifactId>
        <version>${langgraph4j.version}</version>
    </dependency>
    <!-- Integración LangGraph4j <-> LangChain4j (serializers de ChatMessage, AgentExecutor...) -->
    <dependency>
        <groupId>org.bsc.langgraph4j</groupId>
        <artifactId>langgraph4j-langchain4j</artifactId>
        <version>${langgraph4j.version}</version>
    </dependency>

    <!-- AiServices de LangChain4j (IntentClassifier, Responder) -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>

    <!-- Persistencia -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- Validación de DTOs -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
</dependencies>
```

> **⚠️ Nota de compatibilidad (tarea de la Fase 0):** las versiones modernas de `langgraph4j` (≥ 1.0) están alineadas con LangChain4j ≥ 1.0, mientras que el `pom.xml` actual usa LangChain4j `0.36.2` (API antigua: `ChatLanguageModel.generate(...)`). Antes de empezar hay que **elegir el par de versiones** consultando la matriz del repositorio de langgraph4j ([github.com/langgraph4j/langgraph4j](https://github.com/langgraph4j/langgraph4j)) y, muy probablemente, **subir LangChain4j a 1.x** (cambios: `ChatLanguageModel` → `ChatModel`, `generate()` → `chat()`, artefacto `langchain4j-google-ai-gemini` mantiene el nombre). Esa subida afecta a `Config.java` y `LlmService.java` existentes — está recogida como tarea propia en el [plan](09-plan-implementacion.md).

## Configuración por perfiles

`application.yml` (común) — se añade:

```yaml
copilot:
  model-name: gemini-2.5-flash-lite   # modelo para router y responder
  max-recent-orders: 3
```

`application-dev.yml` — se añade:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:copilot;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: none        # el esquema lo crean schema.sql/data.sql
  sql:
    init:
      mode: always
  h2:
    console:
      enabled: true
```

`application-prod.yml` — nuevo (valores por variable de entorno, los inyecta Kubernetes):

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
  sql:
    init:
      mode: never           # en prod el esquema lo gestiona la infra
```

Secretos: `GEMINI_API_KEY` ya se lee de entorno (patrón existente `${GEMINI_API_KEY:}`); en Kubernetes llega vía `Secret` (ver rama de infraestructura). **Nunca** valores reales en el repo.

## Convenciones

- **Idioma:** código e identificadores en inglés; javadoc, logs y prompts en español (coherente con el código existente).
- **Lombok:** `@RequiredArgsConstructor` + `@Slf4j` en servicios/nodos; `@Getter/@Setter` (no `@Data`) en entidades.
- **Records** para todos los DTOs y salidas estructuradas del LLM.
- **Prompts:** siempre en text blocks (`"""`), versionados con el código del nodo que los usa; nada de prompts en `application.yml`.
- **Logs de nodos:** prefijo `---NOMBRE---` en DEBUG (patrón de los ejemplos LangGraph4j), con `conversationId` en el MDC para correlar turnos.
- **Tests:** JUnit 5; unitarios para aristas y tools, `@SpringBootTest` con LLM fakes para el flujo; los tests que llaman a Gemini real van con `@Tag("llm")` y excluidos del build por defecto.

## Ramas de trabajo propuestas

| Rama | Contenido |
|---|---|
| `feature/upgrade-lc4j-1x` | Fase 0: subida LangChain4j 1.x + langgraph4j en el pom, adaptación de `Config`/`LlmService` |
| `feature/domain-jpa` | Fase 1: entidades, repos, seed, perfiles |
| `feature/agent-graph` | Fases 2-3: estado, nodos, grafo, tools |
| `feature/copilot-api` | Fase 4: controller, DTOs, errores |
| `feature/frontend-angular` | Fase 5: SPA (puede ser repo aparte `l1-copilot-frontend`; decisión en el plan) |
