# Playbook de implementación — Mejoras L1 Support Copilot

Documento operativo para retomar cada mejora en una **sesión nueva sin releer el
proyecto**. El flujo previsto: el usuario dice *"haz el problema N"* y se ejecuta solo la
ficha N. Cada ficha es autocontenida.

> Resumen ejecutivo (qué/por qué/cómo, prioridades): [`00-resumen.md`](00-resumen.md).

## Contexto mínimo del proyecto (leer una vez)

- **Backend**: Spring Boot 3.5 + Java 17, en `backend/`. Paquete raíz
  `io.github.mikel0ne.l1copilot`. Maven; correr SIEMPRE desde `backend/`: `cd backend && mvn test`.
- **Agente**: LangGraph4j. Grafo en `agent/SupportAgentGraph.java`, routing determinista en
  `agent/SupportRouting.java`, estado en `agent/SupportState.java`, nodos en `agent/node/`,
  servicios LLM (AiServices) en `agent/llm/`.
- **Puente REST→grafo**: `service/ConversationService.java` (un turno HTTP = una
  invocación del grafo; el checkpointer `MemorySaver` restaura el estado entre turnos).
- **Reglas de negocio en Java** (no en prompt): `tools/OrderTools.java`,
  `tools/TicketTools.java`. Seguridad: los repos filtran SIEMPRE por `userId`.
- **Tests**: `@SpringBootTest` SÍ arranca aquí, pero con los `ChatModel`/AiServices
  **mockeados** (`@MockitoBean`); no se crean modelos reales (el loopback está bloqueado).
  Tests de routing puro sin Spring. Seguridad bajo `-Psecurity` (excluidos por defecto).
- **Modelo LLM**: Gemini vía Google AI Studio, configurado en
  `backend/src/main/resources/application*.yml`.

## Convenciones de las fichas

- **Ficheros**: rutas relativas a la raíz del repo.
- **Verificación**: por defecto `cd backend && mvn test`. Para un test concreto:
  `cd backend && mvn -Dtest=NombreTest test`.
- **Regla de oro**: tras el cambio, los ~28 tests deben seguir verdes. Si tocas
  routing/intents o prompts, considera además el golden-set (`RouterEvalTest`, requiere
  `ROUTER_EVAL=true`).

---

## #1 — Password local fuera del YAML 🟠 ⭐⭐⭐ · riesgo ~0 (config)

- **Problema**: la contraseña de Postgres local está hardcodeada y versionada.
- **Ficheros**:
  - `backend/src/main/resources/application-local.yml` (la línea `password: change-me`).
  - Referencia del patrón correcto: `backend/src/main/resources/application-prod.yml`
    (usa `${SPRING_DATASOURCE_PASSWORD}` / variables de entorno).
- **Enfoque**: sustituir el literal por `${DB_PASSWORD:change-me}` (o el nombre de var
  que use prod) con default explícito para no romper el arranque local de nadie. Verificar
  que `username`/`url` no oculten otros literales sensibles.
- **Tests**: ninguno específico. No afecta a la suite (perfil `local` no se usa en tests).
- **Verificación**: `cd backend && mvn -Dspring-boot.run.profiles=local spring-boot:run`
  arranca si hay Postgres local; si no, basta con `mvn test` (no rompe nada).

## #2 — Eliminar/sanear `LlmController` + `LlmService` 🟡 ⭐⭐⭐

- **Problema**: endpoints `/llm/chat` y `/llm/hello` de andamiaje, DTOs inline, sin
  validación; el servicio es un passthrough trivial.
- **Ficheros**:
  - `backend/src/main/java/io/github/mikel0ne/l1copilot/controller/LlmController.java`
  - `backend/src/main/java/io/github/mikel0ne/l1copilot/service/LlmService.java`
- **Antes de borrar**: comprobar que nadie los usa →
  `grep -rn "LlmService\|/llm" backend/src frontend/src` (frontend Angular incluido).
- **Enfoque**: si no se usan, borrar ambos (y su test si lo hubiera). Si se quieren
  conservar como demo, mover `LlmRequest`/`LlmResponse` a `dto/` como records y validar
  (`@NotBlank @Size`).
- **Tests**: no hay test propio conocido; tras borrar, confirmar que compila.
- **Verificación**: `cd backend && mvn test`.

## #3 — Quitar `candidateLabel()` por reflexión 🟡 ⭐⭐⭐

- **Problema**: método con reflexión para etiquetar candidatos heterogéneos; legacy, los
  candidatos ya se guardan como `String`.
- **Ficheros**:
  - `backend/src/main/java/io/github/mikel0ne/l1copilot/agent/SupportState.java`
    (método privado `candidateLabel(Object)` y su uso en `candidates()`).
  - Dónde se llenan los candidatos (confirmar que ya son texto):
    `backend/src/main/java/io/github/mikel0ne/l1copilot/agent/node/ResolveOrderNode.java`.
  - Test que cubre la normalización: `backend/src/test/java/io/github/mikel0ne/l1copilot/agent/SupportStateTest.java`.
- **Enfoque**: si `candidates` siempre se almacena como `List<String>`, simplificar
  `candidates()` para devolverlos tal cual y eliminar la reflexión. Mantener compatibilidad
  con lo que verifique `SupportStateTest`.
- **Verificación**: `cd backend && mvn -Dtest=SupportStateTest test` y luego `mvn test`.

## #4 — Centralizar reglas de intents 🟠 ⭐⭐⭐

- **Problema**: "qué intent es acción" y "cuál pasa por verify" está disperso y hardcodeado.
- **Ficheros**:
  - `backend/src/main/java/io/github/mikel0ne/l1copilot/agent/Intent.java` (destino de la lógica:
    añadir `isAction()` / `needsVerify()`).
  - `backend/src/main/java/io/github/mikel0ne/l1copilot/agent/SupportRouting.java` (consume las
    listas hoy hardcoded — buscar `CANCEL`/`RETURN`/`SUPPORT`).
  - `backend/src/main/java/io/github/mikel0ne/l1copilot/agent/node/RouterNode.java` (repite parte
    de la decisión).
  - Tests: `agent/SupportRoutingTest.java`, `agent/node/RouterNodeTest.java`.
- **Enfoque**: mover las clasificaciones a métodos del enum `Intent` y que `SupportRouting`
  y `RouterNode` pregunten ahí. Sin cambiar el comportamiento (refactor puro).
- **Verificación**: `cd backend && mvn -Dtest=SupportRoutingTest,RouterNodeTest test`,
  luego `mvn test`. Recomendado correr el golden-set si está cableado.

## #5 — Sincronizar documentación 🟡 ⭐⭐⭐ · riesgo 0 (solo docs)

- **Problema**: README cita `gemini-2.5-*` pero el código usa `gemini-3.1-flash-lite`;
  diagramas del grafo divergentes.
- **Ficheros (solo `.md`)**:
  - `README.md` (raíz) — nombres de modelo y diagrama mermaid.
  - `docs/grafo.md` y `docs/arquitectura/04-grafo-langgraph.md` — diagramas del grafo.
  - Fuente de verdad del modelo: `backend/src/main/resources/application.yml`
    (`langchain4j.gemini.model-name`).
  - Fuente de verdad del grafo: `agent/SupportAgentGraph.java` (método `build()`).
- **Enfoque**: alinear nombres de modelo con `application*.yml`; elegir UN diagrama canónico
  (el de `docs/grafo.md`) y que el README lo referencie en vez de duplicar uno desfasado.
- **Verificación**: revisión visual. No hay tests.

## #6 — Aislamiento de tests 🔴 ⭐⭐

- **Problema**: tests de flujo comparten BD H2; resetean en `@BeforeEach` y restauran en
  `finally` (que puede ocultar fallos del `try`).
- **Ficheros**:
  - `backend/src/test/java/io/github/mikel0ne/l1copilot/agent/SupportAgentFlowTest.java`
    (ver `resetOrderStates()` y los bloques `try/finally` que restauran pedidos).
  - Comparar con el buen patrón: `backend/src/test/java/io/github/mikel0ne/l1copilot/tools/OrderToolsTest.java`
    (`@DataJpaTest`) y `controller/ChatControllerTest.java`.
- **Enfoque**: dar aislamiento real: `@Transactional` con rollback por test, o sembrar
  datos propios por test. Eliminar los `finally` de restauración (que el rollback haga el
  trabajo). Asegurar independencia del orden de ejecución.
- **Verificación**: `cd backend && mvn -Dtest=SupportAgentFlowTest test`; idealmente correr
  la clase en orden aleatorio para confirmar que ya no dependen entre sí.

## #7 — Estado del agente con clave única de verdad 🟠 ⭐⭐

- **Problema**: el estado se accede por claves string sueltas; `ConversationService` repite
  esos literales al inicializar el turno → sin fuente única, typos silenciosos.
- **Ficheros**:
  - `backend/src/main/java/io/github/mikel0ne/l1copilot/agent/SupportState.java` (accesores
    `value("...")`; `SCHEMA` solo declara `history`).
  - `backend/src/main/java/io/github/mikel0ne/l1copilot/service/ConversationService.java`
    (mapa `input` con los `put("userId"...)` ~líneas 52-72).
  - Todos los nodos en `agent/node/` escriben claves del estado (revisar `Map.of(...)`).
- **Enfoque (incremental)**: introducir constantes para las claves (p. ej.
  `SupportState.Keys`) y/o un factory `SupportState.initialTurn(userId, treatment, message)`
  reutilizado por el servicio (y tests). Migrar literales a las constantes sin cambiar
  semántica.
- **Tests**: `RouterNodeTest`, `SupportAgentFlowTest`, `SupportStateTest`.
- **Verificación**: `cd backend && mvn test`.

## #8 — Tarjetas de UI tipadas extremo a extremo 🟠 ⭐⭐

- **Problema**: los nodos crean tarjetas como `List<Map<String,Object>>` y
  `ConversationService` las re-parsea con coerción defensiva repetida 3 veces.
- **Ficheros**:
  - Estado: `agent/SupportState.java` (`orderCards()`/`ticketCards()`/`returnCards()`,
    hoy `List<Map<String,Object>>`).
  - Productores (montan los mapas): `agent/node/ResolveOrderNode.java`,
    `agent/node/TrackingNode.java`, `agent/node/TicketsNode.java`,
    `agent/node/ReturnCheckNode.java` / `ReturnExecNode.java`.
  - Consumidor: `service/ConversationService.java` (mapeo `orderCards().stream()...` con
    `instanceof Number n ? ... : Long.parseLong(...)`).
  - DTOs destino (ya existen): `dto/OrderCardDto.java`, `dto/TicketCardDto.java`,
    `dto/ReturnCardDto.java`.
- **Enfoque**: que el estado transporte directamente los records tipados (o records
  internos del agente equivalentes) y que el servicio los reexponga sin coerción. Empezar
  por un tipo de tarjeta (pedidos) y replicar.
- **Cuidado**: el checkpointer serializa el estado; usar records serializables simples.
- **Tests**: `SupportAgentFlowTest` (verifica tarjetas en varios turnos), `ChatControllerTest`.
- **Verificación**: `cd backend && mvn test` + revisión manual del front (las tarjetas
  deben seguir pintándose y siendo clicables en el listado).

## #9 — Canal nodo→Responder tipado (sin prefijos string) 🟠 ⭐⭐

- **Problema**: nodos escriben instrucciones para el redactor con prefijos (`"CLARIFY:"`…)
  que el prompt del `Responder` reconoce por substring → contrato frágil.
- **Ficheros** (confirmar los prefijos exactos al abrir):
  - Productores: `agent/node/ClarifyNode.java`, `agent/node/SupportNode.java`,
    `agent/node/FallbackNode.java` (construyen `toolResult` con el prefijo).
  - Consumidor: `agent/llm/Responder.java` (el system prompt parsea `CLARIFY:` etc.) y
    `agent/node/RespondNode.java` (arma el contexto del Responder).
- **Enfoque**: modelar la directiva como tipo explícito en el estado (enum
  `ResponderDirective` + payload, o un campo dedicado) y que `RespondNode` lo traduzca a la
  instrucción del prompt en un único sitio. Reduce el acoplamiento por texto a un solo punto.
- **Tests**: `SupportAgentFlowTest` (escenarios de clarify/fallback/support).
- **Verificación**: `cd backend && mvn test`. Al tocar el prompt, revisar golden-set.

## #10 — Unificar nodos Cancel/Return 🟡 ⭐⭐

- **Problema**: `CancelCheckNode`≈`ReturnCheckNode` y `CancelExecNode`≈`ReturnExecNode`
  casi idénticos.
- **Ficheros**:
  - `agent/node/CancelCheckNode.java`, `agent/node/ReturnCheckNode.java`
  - `agent/node/CancelExecNode.java`, `agent/node/ReturnExecNode.java`
  - Tools que invocan: `tools/OrderTools.java` (`checkCancellable`/`cancelOrder` y
    `checkReturnable`/`requestReturn`).
- **Enfoque**: extraer una plantilla/base parametrizada por (tool de check, tool de exec,
  textos, claves de estado `pendingCancelId`/`pendingReturnId`). Mantener los nodos como
  finos adaptadores. No cambiar el grafo (`SupportAgentGraph`) ni el routing.
- **Tests**: `SupportAgentFlowTest` (flujos de cancelación y devolución), `SupportRoutingTest`.
- **Verificación**: `cd backend && mvn test`.

## #11 — Prompts: indicaciones, no frases 🟠 ⭐⭐

- **Problema**: reglas de negocio "encubiertas" como pistas en los prompts.
- **Ficheros**:
  - `agent/llm/Responder.java`, `agent/llm/IntentClassifier.java`, `agent/llm/Verifier.java`
    (system prompts).
  - Golden-set: `agent/RouterEvalTest.java` + el JSON `golden-router-cases.json`
    (buscar con `grep -rn golden backend/src/test`).
- **Enfoque**: revisar contra la norma del proyecto (dar el objetivo a comunicar, no la
  frase; no cruzar temas que el usuario no sacó; datos/estados los decide Java). Mover a
  Java lo que sea regla de negocio. **Es lo más sensible a regresiones de comportamiento**:
  cambiar de poco en poco y medir con el golden-set.
- **Verificación**: `cd backend && ROUTER_EVAL=true mvn -Dtest=RouterEvalTest test` (si está
  cableado) + `mvn test`. Prueba manual de conversación recomendada.

## #12 — Qualifiers tipados para los `ChatModel`/`Responder` 🟡 ⭐⭐

- **Problema**: beans desambiguados por `@Qualifier("string")` (typos no detectados en
  compilación).
- **Ficheros**:
  - `backend/src/main/java/io/github/mikel0ne/l1copilot/config/Config.java` (define los `ChatModel`
    por temperatura).
  - `backend/src/main/java/io/github/mikel0ne/l1copilot/config/AgentConfig.java` (define los
    AiServices `Responder`/`IntentClassifier`/`Verifier`/`TicketWriter` y los inyecta por
    qualifier).
- **Enfoque**: crear anotaciones `@Qualifier` propias (`@Deterministic`, `@ResponseModel`,
  `@ChatModel`, `@TicketModel`) y usarlas en definición e inyección. Refactor puro.
- **Tests**: el arranque del contexto en `@SpringBootTest` ya valida el cableado.
- **Verificación**: `cd backend && mvn test`.

## #13 — CI + cobertura 🟠 ⭐⭐ · riesgo ~0 (build/infra)

- **Problema**: sin pipeline ni cobertura.
- **Ficheros**:
  - Nuevo: `.github/workflows/ci.yml` (build + `mvn test` en push/PR).
  - `backend/pom.xml` (añadir `jacoco-maven-plugin`; ya tiene perfil `security`).
- **Enfoque**: workflow mínimo (checkout, JDK 17, `cd backend && mvn -B test`). Añadir
  JaCoCo con report; umbral opcional para no bloquear de golpe. No toca runtime.
- **Cuidado**: confirmar con el usuario que el repositorio admite GitHub Actions.
- **Verificación**: `cd backend && mvn test` localmente; el workflow se valida al hacer push.

## #14 — Adelgazar `RouterNode` 🟡 ⭐ (esfuerzo alto, dejar para el final)

- **Problema**: `RouterNode` concentra clasificación + foco + navegación + digresión.
- **Ficheros**:
  - `backend/src/main/java/io/github/mikel0ne/l1copilot/agent/node/RouterNode.java`
  - Colaboradores existentes: `agent/Navigation.java`, `agent/Intent.java`,
    `agent/llm/IntentClassifier.java`.
  - Test clave: `agent/node/RouterNodeTest.java` (es la red de seguridad del refactor).
- **Enfoque**: extraer la resolución de foco y la navegación a clases con tests propios,
  dejando `RouterNode` como orquestador. Refactor con cobertura previa: NO empezar sin
  tener `RouterNodeTest` verde. Conviene hacerlo después de #4 y #7.
- **Verificación**: `cd backend && mvn -Dtest=RouterNodeTest test`, luego `mvn test` + golden-set.

## #15 — Validación declarativa en entidades JPA 🟡 ⭐

- **Problema**: entidades sin `@NotNull`/`@NotBlank` (solo `NOT NULL` en SQL).
- **Ficheros**: `domain/Order.java`, `domain/Ticket.java`, `domain/User.java`.
- **Enfoque**: añadir anotaciones de Bean Validation coherentes con `schema.sql`. Verificar
  que el seed (`data.sql`) sigue siendo válido.
- **Verificación**: `cd backend && mvn test` (arranque + `OrderToolsTest` con H2).

## #16 — Menores de la capa web 🟡 ⭐

- **Problema**: `CacheController.clear()` sin protección; `OpenApiConfig` ordena tags a mano.
- **Ficheros**:
  - `controller/CacheController.java` (endpoint `DELETE .../clear`).
  - `config/OpenApiConfig.java` (reordenado manual de tags).
  - `backend/src/main/resources/application.yml` (sección `springdoc`).
- **Enfoque**: restringir/ocultar el endpoint de caché (o documentarlo como interno);
  sustituir el reordenado por `springdoc.swagger-ui.tag-sorter=alpha` si el orden alfabético
  basta, y eliminar el customizer.
- **Verificación**: `cd backend && mvn test`; revisar `/api/swagger-ui` manualmente.

## #17 — Portabilidad de `data.sql` H2↔Postgres 🟡 ⭐

- **Problema**: el seed usa funciones de H2 (`DATEADD`) que pueden no migrar a Postgres.
- **Ficheros**:
  - `backend/src/main/resources/data.sql` (seed).
  - `backend/src/main/resources/schema.sql` (esquema).
  - Perfiles que usan Postgres: `application-local.yml`, `application-prod.yml`.
- **Enfoque**: usar SQL portable (fechas calculadas en Java o sintaxis estándar) o separar
  seeds por dialecto. Verificar contra un Postgres local.
- **Verificación**: `cd backend && mvn test` (H2) y arranque con perfil `local` contra
  Postgres para confirmar el seed.

---

## Orden sugerido de ejecución

1. **Riesgo casi nulo primero**: #5 (docs), #13 (CI), #1 (config).
2. **Quick wins de código**: #2, #3, #4.
3. **Calidad de mayor valor**: #6, #7, #8.
4. **Estructurales**: #9, #10, #12.
5. **Sensible a comportamiento**: #11 (con golden-set).
6. **Al final / mayor esfuerzo**: #14. Menores sueltos: #15, #16, #17.
