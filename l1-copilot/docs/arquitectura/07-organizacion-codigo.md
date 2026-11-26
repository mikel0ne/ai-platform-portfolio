# 07 — Organización del código

## Punto de partida

El proyecto vive en `l1-copilot/backend/` dentro del **monorepo** (rama `main`; tras la
reestructuración "un dir por proyecto", `autoops/` y `l1-copilot/` son proyectos separados con
`infra/`, `docker/` y `scripts/` compartidos en la raíz). El módulo Maven es `l1-copilot-backend`.
El backend aporta:

- Spring Boot **4.1.2** + Java 17 (`pom.xml`).
- Integración Gemini/Google AI Studio (`langchain4j-google-ai-gemini`) y un cliente
  OpenAI-compatible (`langchain4j-open-ai`) para Gemma; beans `ChatModel` en `config/ChatModelConfig.java`,
  propiedades `langchain4j.gemini.*`.
- Caché de respuestas en fichero (`FileCacheManager` + `CachingChatModel`, en `platform-commons`; la clave se construye con campos explícitos del mensaje), Swagger, Actuator.
- Lombok (`lombok.config` en el módulo) y token de demo firmado (`security/TokenSigner`).
- Dependencia del módulo hermano `platform-commons` (ver abajo): para compilar a mano hay que instalarlo antes (`./mvnw -f ../../platform-commons/pom.xml install`) o construir el reactor desde la raíz (`pom.xml` agregador).

## Árbol de paquetes (estado real)

```
l1-copilot/backend/src/main/java/io/github/mikel0ne/l1copilot/
├── Application.java
├── config/
│   ├── ChatModelConfig.java         # beans ChatModel Gemini/Gemma (1 por temperatura/rol)
│   ├── AgentConfig.java             # CompiledGraph + memoria + AiServices
│   ├── CacheConfig.java / CacheProperties.java   # bean de FileCacheManager (la clase vive en platform-commons)
│   ├── ConversationHistoryProperties.java / LangChain4jProperties.java
│   ├── TreatmentPolicy.java         # umbral tú/usted por edad
│   └── qualifier/                   # @Deterministic, @TicketModel, @ResponseModel,
│                                    #   @ChattyModel, @ChattyResponder
├── controller/
│   ├── AuthController.java          # POST /api/copilot/login (token de demo firmado)
│   ├── ChatController.java          # /api/copilot/** (chat + users)
│   ├── TicketController.java        # /api/copilot/tickets/{id}
│   ├── ReturnController.java        # /api/copilot/returns/{id}
│   ├── CacheController.java         # /api/cache/** (solo con cache.enabled=true; clear() oculto: @Hidden)
│   ├── SessionUsers.java            # valida el Bearer y devuelve el userId
│   └── ApiExceptionHandler.java / ResourceNotFoundException.java / UnknownUserException.java
├── security/
│   └── TokenSigner.java             # token de demo: base64url(userId).base64url(HMAC-SHA256)
├── service/
│   ├── ConversationService.java     # puente REST→grafo (invoca por threadId)
│   ├── OrderService.java / TicketService.java / OrderNotFoundException.java
│   └── TurnLogger.java              # registro de turnos (turn_log) para evaluación
├── agent/                           # todo el agente LangGraph4j
│   ├── SupportAgentGraph.java       # builder del StateGraph (15 nodos)
│   ├── SupportRouting.java          # aristas condicionales (funciones estáticas + TO_*)
│   ├── SupportState.java            # AgentState + SCHEMA (solo canal 'history')
│   ├── Turn.java                    # entrada del historial (rol + texto)
│   ├── BoundedMemorySaver.java      # checkpointer en memoria con tope
│   ├── Intent.java / Awaiting.java / Verdict.java / Navigation.java / Confirmation.java / Treatment.java
│   ├── AssentGuard.java             # un asentimiento suelto sin nada pendiente no cambia de intención
│   ├── UiAction.java                # parser de acciones de la UI (clics/botones)
│   ├── node/                        # 15 nodos + colaboradores del router:
│   │   ├── RouterNode.java          # orquestador fino: atajos → LLM → navegación → políticas
│   │   ├── RouterShortcuts.java     # botones/tarjetas deterministas, antes del LLM
│   │   ├── RouterNavigation.java    # BACK / BACK_TO_LIST / BACK_TO_MENU (retorno anticipado)
│   │   ├── RouterTurn.java          # contexto del turno: estado, decisión y actualizaciones
│   │   ├── RouterPolicies.java      # pasos ordenados (digresión, intención, borrador, foco, confirmación, aclaración)
│   │   ├── OrderCandidates.java     # formato y reconocimiento de los candidatos (lo usa también ResolveOrderNode)
│   │   ├── VerifyNode.java / ClarifyNode.java
│   │   ├── ResolveOrderNode.java / TrackingNode.java
│   │   ├── CancelCheckNode.java / CancelExecNode.java
│   │   ├── ReturnCheckNode.java / ReturnExecNode.java
│   │   ├── SupportNode.java / TicketsNode.java
│   │   ├── FaqNode.java / ChattyNode.java / FallbackNode.java
│   │   └── RespondNode.java
│   └── llm/                         # AiServices (interfaces con @SystemMessage) y soporte:
│       ├── IntentClassifier.java / Responder.java / Verifier.java
│       ├── TicketWriter.java / FaqKnowledgeBase.java
│       └── ParsingVerifier.java / RawVerifier.java / DecisionParser.java / PromptData.java
├── resilience/                      # reintentos del LLM: RetryingChatModel, LlmFailureClassifier,
│                                    #   GoogleAiFailureClassifier, LlmRetryProperties
├── observability/                   # Langfuse ligado a Spring: LangfuseNodeHook, LangfuseTurnTracer,
│                                    #   LangfuseTracingConfig, LangfuseProperties, LangfuseScoreGateway,
│                                    #   LangfuseTraceAttributesProcessor
├── domain/
│   ├── Order.java / OrderStatus.java
│   ├── Ticket.java / TicketStatus.java
│   ├── User.java
│   └── repository/ (OrderRepository, TicketRepository, UserRepository)
└── dto/
    ├── ChatRequest.java / ChatResponse.java / UserDto.java
    ├── LoginRequest.java / LoginResponse.java
    ├── ConfirmActionDto.java
    ├── OrderCardDto.java / TicketCardDto.java / TicketDetailDto.java
    ├── ReturnCardDto.java / ReturnLabelDto.java
    └── result/ (OrderSummary, ShipmentStatus, CancellationCheck, CancellationResult,
               ReturnCheck, ReturnResult, TicketSummary, TicketCreated)

platform-commons/src/main/java/io/github/mikel0ne/platform/commons/   # librería sin Spring, compartida con AutoOps
├── cache/                           # FileCacheManager, CachingChatModel, CacheHitListener
└── langfuse/                        # LangfuseAttributes, LangfuseChatModelListener, LangfuseCacheHitTracer
```

Criterios:

- **`agent/` no conoce HTTP** (no importa nada de `controller/`); `controller/` no conoce LangGraph4j. El puente es `ConversationService`.
- **`service/` (`OrderService`, `TicketService`) no conoce el grafo**: reciben primitivas y devuelven records de `dto/result`. Reutilizables desde un futuro `AiServices` con function-calling.
- Los **AiServices** (interfaces con `@SystemMessage`) viven junto al agente (`agent/llm/`) porque sus prompts son parte de la lógica del grafo.

## Dependencias Maven principales

```xml
<properties>
    <java.version>17</java.version>
    <!-- Par validado en la Fase 0 contra Maven Central -->
    <langchain4j.version>1.23.0</langchain4j.version>
    <langgraph4j.version>1.8.27</langgraph4j.version>
</properties>

<dependencies>
    <!-- Librería compartida sin Spring: caché de respuestas del LLM y trazas Langfuse -->
    <dependency>
        <groupId>io.github.mikel0ne</groupId>
        <artifactId>platform-commons</artifactId>
        <version>1.0.0</version>
    </dependency>

    <!-- Lombok (optional) -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>

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

> **✅ Nota de compatibilidad:** el par vigente es **LangChain4j 1.23.0 + LangGraph4j 1.8.27** (langgraph4j 1.8.x compila contra langchain4j 1.14+, Java 17; hay que subir los dos a la vez), y el mismo en AutoOps y en `platform-commons`. Migración de API 0.36→1.x aplicada: `ChatLanguageModel` → `ChatModel`, `generate()` → `chat()`. No hizo falta el módulo `langgraph4j-langchain4j`: el estado del grafo usa solo tipos `Serializable` simples, así que basta `langgraph4j-core` con el serializer por defecto.

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
      platform: h2
  h2:
    console:
      enabled: true
```

`application-prod.yml` — perfil de Kubernetes (usuario y contraseña llegan por variable de entorno desde Secrets; `ddl-auto: none` y `sql.init.mode: never` vienen del `application.yml` común):

```yaml
server:
  port: 8080   # debe coincidir con container.port de los values de Helm

spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://postgres.infra.svc.cluster.local:5432/l1_copilot}
```

El esquema y el seed los aplica una vez `l1-copilot/scripts/setup-l1-copilot-db.sh`.

Secretos: `GEMINI_API_KEY` ya se lee de entorno (patrón existente `${GEMINI_API_KEY:}`); en Kubernetes llega vía `Secret` (ver rama de infraestructura). **Nunca** valores reales en el repo.

## Convenciones

- **Idioma:** código e identificadores en inglés; javadoc, logs y prompts en español (coherente con el código existente).
- **Lombok:** `@RequiredArgsConstructor` + `@Slf4j` en servicios/nodos; `@Getter/@Setter` (no `@Data`) en entidades.
- **Records** para todos los DTOs y salidas estructuradas del LLM.
- **Prompts:** los cuatro prompts de sistema largos (router, verificador, responder, redactor de tickets) en `src/main/resources/prompts/*.txt` (`@SystemMessage(fromResource = ...)`), las plantillas cortas del mensaje de usuario en text blocks (`"""`); versionados con el código del nodo que los usa y nada de prompts en `application.yml`. LangChain4j lee el recurso con el charset por defecto de la JVM: por eso se arranca con `-Dfile.encoding=UTF-8` y `CharsetGuard` aborta el arranque si no es UTF-8.
- **Logs de nodos:** prefijo `---NOMBRE---` en DEBUG (patrón de los ejemplos LangGraph4j); con Langfuse activo el `traceId` va en el MDC para correlar turnos.
- **Tests:** JUnit 5; unitarios para aristas y tools, `@SpringBootTest` con LLM fakes (`@MockitoBean`) para el flujo; los tests que llaman al modelo real (`RouterEvalTest`, `RouterFailureE2eEvalTest`) son opt-in por variable de entorno y no corren en el build. `@SpringBootTest` solo corre en Linux en este entorno: `scripts/verify-in-docker.sh`.

## Ramas de trabajo (histórico del plan)

> **Nota:** este reparto en feature-branches fue el plan inicial; **no** se siguió tal cual.
> El proyecto avanzó por fases sobre el monorepo y hoy todo vive en `main` bajo `l1-copilot/`
> (backend y frontend en el mismo proyecto, no en repos separados). Se conserva como
> referencia histórica de las fases.

| Fase | Contenido |
|---|---|
| Fase 0 | Subida LangChain4j 1.x + langgraph4j en el pom, adaptación de `Config` (y retirada del andamiaje `LlmService`) |
| Fase 1 | Entidades, repos, seed, perfiles |
| Fases 2-3 | Estado, nodos, grafo, tools |
| Fase 4 | Controller, DTOs, errores |
| Fase 5 | SPA Angular (en `l1-copilot/frontend/`, mismo proyecto) |
