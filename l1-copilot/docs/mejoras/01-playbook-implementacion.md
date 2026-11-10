# Playbook de implementación — Mejoras L1 Support Copilot

Documento operativo para retomar cada mejora en una **sesión nueva sin releer el
proyecto**. El flujo previsto: el usuario dice *"haz el problema N"* y se ejecuta solo la
ficha N. Cada ficha es autocontenida.

> Resumen ejecutivo (qué/por qué/cómo, prioridades): [`00-resumen.md`](00-resumen.md).

## Contexto mínimo del proyecto (leer una vez)

- **Backend**: Spring Boot 3.5 + Java 17, en `l1-copilot/backend/` (módulo Maven
  `l1-copilot-backend`). Paquete raíz `io.github.mikel0ne.l1copilot`. Correr SIEMPRE desde el
  backend: `cd l1-copilot/backend && mvn test`.
- **Agente**: LangGraph4j. Grafo en `agent/SupportAgentGraph.java`, routing determinista en
  `agent/SupportRouting.java`, estado en `agent/SupportState.java`, nodos en `agent/node/`,
  servicios LLM (AiServices) en `agent/llm/`.
- **Puente REST→grafo**: `service/ConversationService.java` (un turno HTTP = una
  invocación del grafo; el checkpointer `MemorySaver` restaura el estado entre turnos).
- **Reglas de negocio en Java** (no en prompt): `tools/OrderService.java`,
  `tools/TicketService.java`. Seguridad: los repos filtran SIEMPRE por `userId`.
- **Tests**: `@SpringBootTest` SÍ arranca aquí, pero con los `ChatModel`/AiServices
  **mockeados** (`@MockitoBean`); no se crean modelos reales (el loopback está bloqueado).
  Tests de routing puro sin Spring. Seguridad con `@Tag("security")`, incluida en `verify` (solo esa suite: `-Dgroups=security`).
- **Modelo LLM**: Gemini vía Google AI Studio, configurado en
  `backend/src/main/resources/application*.yml`.

## Convenciones de las fichas

- **Ficheros**: rutas relativas a la raíz del **proyecto** (`l1-copilot/`); p. ej.
  `backend/...` es `l1-copilot/backend/...` y `docs/...` es `l1-copilot/docs/...`.
- **Verificación**: por defecto `cd backend && mvn test`. Para un test concreto:
  `cd backend && mvn -Dtest=NombreTest test`.
- **Regla de oro**: tras el cambio, los ~28 tests deben seguir verdes. Si tocas
  routing/intents o prompts, considera además el golden-set (`RouterEvalTest`, requiere
  `ROUTER_EVAL=true`).

---

## #1 — Password local fuera del YAML 🟠 ⭐⭐⭐ · riesgo ~0 (config) ✅ DONE

**Estado:** COMPLETADO. La contraseña se lee de `${SPRING_DATASOURCE_PASSWORD}`
en `application-local.yml`.

- **Qué se hizo**: se sustituyó el literal `password: <POSTGRES_PASSWORD>` por
  `${SPRING_DATASOURCE_PASSWORD}` en
  `backend/src/main/resources/application-local.yml` (el perfil base `application.yml` usa
  `${SPRING_DATASOURCE_PASSWORD}` sin default).
- **Verificación**: `cd backend && mvn test` (no afecta a los tests: perfil `local` se usa solo
  en desarrollo manual). Si se arranca con perfil `local` contra Postgres, se lee de
  `SPRING_DATASOURCE_PASSWORD` si existe, o usa el default si no.

## #2 — Eliminar/sanear `LlmController` + `LlmService` 🟡 ⭐⭐⭐ ✅ DONE

**Estado:** COMPLETADO. Se borraron `LlmController` y `LlmService` (nadie los usaba:
ni el grafo, ni el routing, ni el frontend). Se limpiaron las referencias en
`Application.java` (líneas de log de `/api/llm/*`) y el javadoc de `Config.java`. El
bean `ChatModel` primario sigue existiendo: lo usan router y verificador.

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

## #3 — Quitar `candidateLabel()` por reflexión 🟡 ⭐⭐⭐ ✅ DONE

**Estado:** COMPLETADO. `SupportState.candidates()` devuelve la lista tal cual
(`this.value("candidates", List::of)`); se eliminó el método `candidateLabel(Object)`
y el import de reflexión. `ResolveOrderNode` ya guardaba los candidatos como `String`.
`SupportStateTest` se reescribió para verificar el paso a través de texto y el
default a lista vacía (los 2 tests verdes).

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

- **Problema**: varios docs citan `gemini-2.5-*` pero el código usa `gemini-3.1-flash-lite`;
  el diagrama y el `SCHEMA`/estado del grafo en `docs/arquitectura/04-grafo-langgraph.md`
  divergen del código (15 nodos reales). Nota: el `README.md` raíz **no** menciona
  l1-copilot (es solo de AutoOps), así que el desfase de modelo está en los docs del
  proyecto, no en el README raíz.
- **Ficheros (solo `.md`)**:
  - Desfase de modelo `gemini-2.5-*`: `docs/arquitectura/01-vision-y-alcance.md`,
    `02-arquitectura-sistema.md`, `07-organizacion-codigo.md`, `09-plan-implementacion.md`
    y `docs/infraestructura/04-backend.md`.
  - Diagrama/estado del grafo: `docs/arquitectura/04-grafo-langgraph.md` (no existe
    `docs/grafo.md`).
  - Fuente de verdad del modelo: `backend/src/main/resources/application.yml`
    (`langchain4j.gemini.model-name: gemini-3.1-flash-lite`).
  - Fuente de verdad del grafo: `agent/SupportAgentGraph.java` (método `build()`) y
    `agent/SupportState.java` (estado real).
- **Enfoque**: alinear nombres de modelo con `application*.yml`; regenerar el diagrama y la
  descripción del estado en `04-grafo-langgraph.md` a partir del código.
- **Verificación**: revisión visual. No hay tests.

## #6 — Aislamiento de tests 🔴 ⭐⭐ ✅ DONE

**Estado:** COMPLETADO (commit `1956513`). `SupportAgentFlowTest` es `@Transactional` con
rollback automático por test. Eliminados todos los bloques `finally` enmascarador y
`@BeforeEach resetOrderStates()`.

- **Qué se hizo**:
  - Añadida anotación `@Transactional` a `SupportAgentFlowTest`.
  - Eliminado el método `@BeforeEach resetOrderStates()`.
  - Eliminados 3 bloques `try/finally` de restauración de pedidos en tests de devolución:
    `returnRequiresConfirmationViaButton`, `alreadyReturningReoffersLabel`, `trackingOrderInReturnShowsLabel`.
  - Actualizado el javadoc de la clase explicando cómo funciona el aislamiento.
- **Por qué funciona**: el grafo corre síncronamente en el hilo del test; todas las tools
  (`OrderService`, `TicketService`) usan `@Transactional(REQUIRED)` → se unen a la transacción
  del test. Al terminar cada test, Spring hace rollback y la BD H2 vuelve al seed. Además,
  cada test usa `conversationId` único → el checkpointer `MemorySaver` no filtra estado
  entre tests (ejecución en cualquier orden sin problema).
- **Verificación**: `cd backend && mvn -Dtest=SupportAgentFlowTest test` (requiere loopback
  disponible; en este entorno hay bloqueo de MemoryIO).

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
  - Tools que invocan: `tools/OrderService.java` (`checkCancellable`/`cancelOrder` y
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

## #12 — Qualifiers tipados para los `ChatModel`/`Responder` 🟡 ⭐⭐ ✅ DONE

**Estado:** COMPLETADO. Anotaciones `@Qualifier` propias en
`config/qualifier/`: `@Deterministic` (0.0, primario), `@TicketModel` (0.2),
`@ResponseModel` (0.35) y `@ChattyModel` (0.7) para los `ChatModel`; además
`@ChattyResponder` para el bean `Responder` `chattyResponder` (antes
`@Qualifier("chattyResponder")` en `RespondNode`). `Config` pone las anotaciones de
`ChatModel` en la definición de cada bean; `AgentConfig` las usa en la inyección
(también en `intentClassifier`/`verifier`, que antes inyectaban el `ChatModel` sin
cualificar) y marca `chattyResponder` con `@ChattyResponder`; `RespondNode` inyecta con
esa misma anotación. Se llama `@ChattyModel`/`@ChattyResponder` y no `@ChatModel`/
`@Responder` para no colisionar con esos tipos. Ya no queda ningún
`@Qualifier("string")` en el proyecto. Compila y el cableado se valida al arrancar el
contexto.

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

## #13 — CI + cobertura 🟠 ⭐⭐ · riesgo ~0 (build/infra) 🟦 PARCIAL

**Estado:** COMPLETADO. JaCoCo está configurado en `backend/pom.xml` y
`.github/workflows/ci.yml` ejecuta `./mvnw verify`, que incluye la suite de seguridad, en cada push.

- **Hecho**:
  - `jacoco-maven-plugin` en `backend/pom.xml` con `prepare-agent` + `report` (genera
    reporte en `target/site/jacoco/`). **Sin umbral que bloquee el build** (medición, no
    gate).
- **Pendiente**:
  - Crear `.github/workflows/ci.yml`: build + `mvn -B test` desde `l1-copilot/backend/` con
    JDK 17, en push/PR a cualquier rama.
  - (Opcional) Convertir el umbral de cobertura en bloqueante cuando interese.
- **Verificación**:
  - Localmente: `cd l1-copilot/backend && mvn test` (ya genera el reporte JaCoCo).
  - En remoto: una vez creado el workflow, se ejecutará en cada push/PR (pestaña "Actions").

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

## #15 — Validación declarativa en entidades JPA 🟡 ⭐ ✅ DONE

**Estado:** COMPLETADO. Bean Validation coherente con `schema-h2.sql`/
`schema-postgresql.sql`: `Order` (`@NotBlank` userId/description, `@NotNull`
status/orderDate), `Ticket` (`@NotBlank` userId/subject/description, `@NotNull`
status/createdAt; `orderId` queda opcional), `User` (`@NotBlank` id/name/email,
`@Email` en email, `@Positive` en age). El seed de demo cumple todas las
restricciones; `OrderServiceTest` (9 tests) sigue verde con el seed real en H2.

- **Problema**: entidades sin `@NotNull`/`@NotBlank` (solo `NOT NULL` en SQL).
- **Ficheros**: `domain/Order.java`, `domain/Ticket.java`, `domain/User.java`.
- **Enfoque**: añadir anotaciones de Bean Validation coherentes con `schema-h2.sql`/
  `schema-postgresql.sql`. Verificar que el seed (`data-h2.sql`) sigue siendo válido.
- **Verificación**: `cd backend && mvn test` (arranque + `OrderServiceTest` con H2).

## #16 — Menores de la capa web 🟡 ⭐ ✅ DONE

**Estado:** COMPLETADO. `CacheController.clear()` marcado `@Hidden` (operación
destructiva interna/operacional, fuera de la API pública; el control de acceso real
queda pendiente de Spring Security). Se borró `OpenApiConfig` (el customizer manual de
tags) y se delega el orden en springdoc: `springdoc.swagger-ui.tags-sorter=alpha` +
`operations-sorter=alpha` en `application.yml`. Nota: el orden ahora es alfabético puro,
así que Actuator ya no queda forzado al final (tradeoff aceptado por simplicidad).

- **Problema**: `CacheController.clear()` sin protección; `OpenApiConfig` ordena tags a mano.
- **Ficheros**:
  - `controller/CacheController.java` (endpoint `DELETE .../clear`).
  - `config/OpenApiConfig.java` (reordenado manual de tags).
  - `backend/src/main/resources/application.yml` (sección `springdoc`).
- **Enfoque**: restringir/ocultar el endpoint de caché (o documentarlo como interno);
  sustituir el reordenado por `springdoc.swagger-ui.tag-sorter=alpha` si el orden alfabético
  basta, y eliminar el customizer.
- **Verificación**: `cd backend && mvn test`; revisar `/api/swagger-ui` manualmente.

## #17 — Portabilidad de `data.sql` H2↔Postgres 🟡 ⭐ ✅ DONE

**Estado:** COMPLETADO. Se separó el seed por dialecto de forma estanca. `schema.sql` y
`data.sql` (los genéricos, con `DATEADD` de H2) se renombraron a `schema-h2.sql` y
`data-h2.sql`, y el perfil `dev` declara `spring.sql.init.platform=h2`. Así Spring carga
SOLO los scripts del dialecto activo: dev → `*-h2.sql`; `local`/`prod` → `*-postgresql.sql`.
Antes, en perfiles Postgres, el mecanismo de fallback de Spring cargaba TAMBIÉN el
`data.sql` genérico (su `DATEADD` reventaba en Postgres con `SQL_INIT_MODE=always`).
`OrderServiceTest` valida la carga del seed H2; docs de arquitectura actualizadas.

- **Problema**: el seed usa funciones de H2 (`DATEADD`) que pueden no migrar a Postgres.
- **Ficheros**:
  - `backend/src/main/resources/data-h2.sql` (seed H2; antes `data.sql`).
  - `backend/src/main/resources/schema-h2.sql` (esquema H2; antes `schema.sql`).
  - `backend/src/main/resources/data-postgresql.sql` / `schema-postgresql.sql` (Postgres).
  - Perfiles: `application-dev.yml` (`platform=h2`), `application-local.yml`,
    `application-prod.yml` (`platform=postgresql`).
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
