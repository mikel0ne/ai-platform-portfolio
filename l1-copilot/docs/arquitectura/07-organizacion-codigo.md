# 07 — Organización del código

## Punto de partida

El proyecto vive en `l1-copilot/backend/` dentro del **monorepo** (rama `main`; tras la
reestructuración "un dir por proyecto", `autoops/` y `l1-copilot/` son proyectos separados con
`infra/`, `docker/` y `scripts/` compartidos en la raíz). El módulo Maven es `l1-copilot-backend`.
El backend aporta:

- Spring Boot **3.5.15** + Java 17 (`pom.xml`).
- Integración Gemini/Google AI Studio (`langchain4j-google-ai-gemini`) y un cliente
  OpenAI-compatible (`langchain4j-open-ai`) para Gemma; beans `ChatModel` en `config/Config.java`,
  propiedades `langchain4j.gemini.*`.
- Caché de respuestas en fichero (`cache/FileCacheManager` + `CachingChatModel`), Swagger, Actuator.

## Árbol de paquetes (estado real)

```
l1-copilot/backend/src/main/java/io/github/mikel0ne/l1copilot/
├── Application.java
├── config/
│   ├── Config.java                  # beans ChatModel Gemini (1 por temperatura/rol)
│   ├── AgentConfig.java             # CompiledGraph + MemorySaver + AiServices
│   ├── CacheInitializer.java / CacheProperties.java
│   ├── ConversationHistoryProperties.java / LangChain4jProperties.java
│   ├── TreatmentPolicy.java         # umbral tú/usted por edad
│   └── qualifier/                   # @Deterministic, @TicketModel, @ResponseModel,
│                                    #   @ChattyModel, @ChattyResponder (ver #12)
├── controller/
│   ├── ChatController.java          # /api/copilot/** (chat + users)
│   ├── TicketController.java        # /api/copilot/tickets/{id}
│   ├── ReturnController.java        # /api/copilot/returns/{id}
│   ├── CacheController.java         # /api/cache/** (clear() oculto: @Hidden)
│   └── ApiExceptionHandler.java
├── service/
│   ├── ConversationService.java     # puente REST→grafo (invoca por threadId)
│   └── TurnLogger.java              # registro de turnos (turn_log) para evaluación
├── agent/                           # todo el agente LangGraph4j
│   ├── SupportAgentGraph.java       # builder del StateGraph (15 nodos)
│   ├── SupportRouting.java          # aristas condicionales (funciones estáticas + TO_*)
│   ├── SupportState.java            # AgentState + SCHEMA (solo canal 'history')
│   ├── Intent.java / Awaiting.java / Verdict.java / Navigation.java / Treatment.java
│   ├── UiAction.java                # parser de acciones de la UI (clics/botones)
│   ├── node/                        # 15 nodos:
│   │   ├── RouterNode.java / VerifyNode.java / ClarifyNode.java
│   │   ├── ResolveOrderNode.java / TrackingNode.java
│   │   ├── CancelCheckNode.java / CancelExecNode.java
│   │   ├── ReturnCheckNode.java / ReturnExecNode.java
│   │   ├── SupportNode.java / TicketsNode.java
│   │   ├── FaqNode.java / ChattyNode.java / FallbackNode.java
│   │   └── RespondNode.java
│   └── llm/                         # AiServices (interfaces con @SystemMessage):
│       ├── IntentClassifier.java / Responder.java / Verifier.java
│       ├── TicketWriter.java / FaqKnowledgeBase.java
├── tools/
│   ├── OrderService.java / TicketService.java
│   └── OrderNotFoundException.java
├── domain/
│   ├── Order.java / OrderStatus.java
│   ├── Ticket.java / TicketStatus.java
│   ├── User.java
│   └── repository/ (OrderRepository, TicketRepository, UserRepository)
├── dto/
│   ├── ChatRequest.java / ChatResponse.java / UserDto.java
│   ├── ConfirmActionDto.java
│   ├── OrderCardDto.java / TicketCardDto.java / TicketDetailDto.java
│   ├── ReturnCardDto.java / ReturnLabelDto.java
│   └── tools/ (OrderSummary, ShipmentStatus, CancellationCheck, CancellationResult,
│              ReturnCheck, ReturnResult, TicketSummary, TicketCreated)
└── cache/ (CacheEntry, CachingChatModel, FileCacheManager: ficheros de texto, sin deserialización Java)
```

Criterios:

- **`agent/` no conoce HTTP** (no importa nada de `controller/`); `controller/` no conoce LangGraph4j. El puente es `ConversationService`.
- **`tools/` no conoce el grafo**: reciben primitivas y devuelven records de `dto/result`. Reutilizables desde un futuro `AiServices` con function-calling.
- Los **AiServices** (interfaces con `@SystemMessage`) viven junto al agente (`agent/llm/`) porque sus prompts son parte de la lógica del grafo.

## Dependencias Maven a añadir

```xml
<properties>
    <java.version>17</java.version>
    <!-- Par validado en la Fase 0 contra Maven Central -->
    <langchain4j.version>1.16.2</langchain4j.version>
    <langgraph4j.version>1.8.17</langgraph4j.version>
</properties>

<dependencies>
    <!-- LangGraph4j: el motor del grafo de estado -->
    <dependency>
        <groupId>org.bsc.langgraph4j</groupId>
        <artifactId>langgraph4j-core</artifactId>
        <version>${langgraph4j.version}</version>
    </dependency>

    <!-- AiServices de LangChain4j (IntentClassifier, Responder, Verifier, TicketWriter) -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j</artifactId>
    </dependency>
    <!-- Cliente nativo Gemini (Google AI Studio) -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-google-ai-gemini</artifactId>
    </dependency>
    <!-- Cliente OpenAI-compatible (modelos Gemma vía endpoint compatible) -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-open-ai</artifactId>
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

> **✅ Nota de compatibilidad (resuelta en la Fase 0):** se fijó **LangChain4j 1.16.2 + LangGraph4j 1.8.17** (langgraph4j 1.8.x compila contra langchain4j 1.14+, Java 17; verificado contra el POM de `langgraph4j-parent` en Maven Central). Migración de API 0.36→1.x aplicada: `ChatLanguageModel` → `ChatModel`, `generate()` → `chat()`. No hizo falta el módulo `langgraph4j-langchain4j`: el estado del grafo usa solo tipos `Serializable` simples, así que basta `langgraph4j-core` con el serializer por defecto.

## Configuración por perfiles

`application.yml` (común) — se añade:

```yaml
langchain4j:
  gemini:
    model-name: gemini-3.1-flash-lite   # modelo para router, verify y responder
```

> El `pom.xml` real añade además `jacoco-maven-plugin` (cobertura, sin umbral bloqueante) y
> los tests `@Tag("security")` forman parte de `./mvnw verify`; `-Dgroups=security` ejecuta solo esa suite.

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
      ddl-auto: none        # el esquema lo crean schema-h2.sql/data-h2.sql (platform=h2)
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

## Ramas de trabajo (histórico del plan)

> **Nota:** este reparto en feature-branches fue el plan inicial; **no** se siguió tal cual.
> El proyecto avanzó por fases sobre el monorepo y hoy todo vive en `main` bajo `l1-copilot/`
> (backend y frontend en el mismo proyecto, no en repos separados). Se conserva como
> referencia histórica de las fases.

| Fase | Contenido |
|---|---|
| Fase 0 | Subida LangChain4j 1.x + langgraph4j en el pom, adaptación de `Config` (y retirada del andamiaje `LlmService`, ver mejora #2) |
| Fase 1 | Entidades, repos, seed, perfiles |
| Fases 2-3 | Estado, nodos, grafo, tools |
| Fase 4 | Controller, DTOs, errores |
| Fase 5 | SPA Angular (en `l1-copilot/frontend/`, mismo proyecto) |
