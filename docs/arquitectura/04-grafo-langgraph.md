# 04 — El grafo LangGraph4j

Documento central del agente. Define el estado, los nodos, las aristas y el ciclo conversacional, usando estrictamente la API de **LangGraph4j** (`org.bsc.langgraph4j`): `StateGraph`, `AgentState`, `Channels`, `node_async`/`edge_async`, `CompiledGraph`, checkpointers. Referencia local: `llms-lgraph.txt`.

## Topología del grafo

```mermaid
graph TB
    START((START)) --> router

    router{{"router<br/>(LLM: clasifica intención<br/>+ extrae orderId si aparece)"}}

    router -->|TRACKING / CANCEL / RETURN<br/>sin orderId| resolve_order
    router -->|TRACKING con orderId| tracking
    router -->|CANCEL con orderId| cancel_check
    router -->|RETURN con orderId| return_check
    router -->|SUPPORT| support
    router -->|FAQ| faq
    router -->|CHATTY| chatty
    router -->|OTHER| fallback

    resolve_order["resolve_order<br/>(tool: getRecentOrders<br/>o mapea respuesta del usuario)"]
    resolve_order -->|resuelto e intent=TRACKING| tracking
    resolve_order -->|resuelto e intent=CANCEL| cancel_check
    resolve_order -->|resuelto e intent=RETURN| return_check
    resolve_order -->|ambiguo: preguntar| respond

    tracking["tracking<br/>(tool: trackShipment)"] --> respond

    cancel_check["cancel_check<br/>(tool: checkCancellable)"]
    cancel_check -->|cancelable, sin confirmar| respond
    cancel_check -->|confirmado por usuario| cancel_exec
    cancel_check -->|no cancelable| respond
    cancel_exec["cancel_exec<br/>(tool: cancelOrder)"] --> respond

    return_check["return_check<br/>(tool: checkReturnable)"]
    return_check -->|devolvible, sin confirmar| respond
    return_check -->|confirmado por usuario| return_exec
    return_check -->|no devolvible| respond
    return_exec["return_exec<br/>(tool: requestReturn)"] --> respond

    support["support<br/>(recopila datos del ticket<br/>tool: createTicket cuando completo)"] --> respond

    faq["faq<br/>(base de conocimiento estática:<br/>faq.md vía FaqKnowledgeBase)"] --> respond

    chatty["chatty<br/>(charla informal: respuesta<br/>neutra, no toca el negocio)"] --> respond

    fallback["fallback<br/>(capacidades; o modo aclaración<br/>sobre el pedido en foco)"] --> respond

    respond["respond<br/>(LLM: redacta la respuesta<br/>final del turno en español)"] --> END((END))
```

Puntos clave:

- **Cada turno HTTP recorre el grafo de `START` a `END`** (ADR-2). El "ciclo" conversacional se materializa entre turnos: el grafo termina con una pregunta, el checkpointer conserva el estado y el siguiente mensaje del usuario reentra por `router`, que ahora ve en el estado que hay una clarificación pendiente.
- `resolve_order` es el **subflujo reutilizable de desambiguación**: lo comparten TRACKING, CANCEL y RETURN.
- `respond` es el único nodo que redacta texto para el usuario: centraliza tono, idioma y formato.

## El estado: `SupportState`

Extiende `org.bsc.langgraph4j.state.AgentState`. El esquema define cómo se fusionan las actualizaciones parciales que devuelve cada nodo (`Channels.base` = reemplazo, `Channels.appender` = acumulación en lista).

```java
public class SupportState extends AgentState {

    public static final Map<String, Channel<?>> SCHEMA = Map.of(
        "messages",        Channels.appender(ArrayList::new), // historial ChatMessage serializado
        "userId",          Channels.base(() -> ""),
        "intent",          Channels.base(() -> Intent.NONE.name()), // TRACKING|CANCEL|RETURN|SUPPORT|TICKETS|FAQ|CHATTY|OTHER|NONE
        "suspendedIntent", Channels.base(() -> (String) null),       // gestión en pausa por una digresión (FAQ/CHATTY)
        "clarifyAttempts", Channels.base(() -> 0),                   // turnos ambiguos seguidos con gestión en curso
        "orderId",         Channels.base(() -> (Long) null),
        "candidateOrders", Channels.base(List::of),           // List<OrderSummary> ofrecidos al usuario
        "awaiting",        Channels.base(() -> Awaiting.NOTHING.name()), // NOTHING|ORDER_CHOICE|CANCEL_CONFIRM|RETURN_CONFIRM|TICKET_CONFIRM|TICKET_INFO
        "pendingCancelId", Channels.base(() -> 0L),                   // pedido a la espera de confirmar cancelación (botón)
        "pendingReturnId", Channels.base(() -> 0L),                   // pedido a la espera de confirmar devolución (botón)
        "ticketDraft",     Channels.base(() -> (String) null),
        "toolResult",      Channels.base(() -> (String) null), // resultado serializado de la última tool
        "resolved",        Channels.base(() -> false)
    );

    public SupportState(Map<String, Object> initData) { super(initData); }

    public String userId()            { return this.<String>value("userId").orElseThrow(); }
    public Intent intent()            { return Intent.valueOf(this.<String>value("intent").orElse("NONE")); }
    public Optional<Long> orderId()   { return this.value("orderId"); }
    public Awaiting awaiting()        { return Awaiting.valueOf(this.<String>value("awaiting").orElse("NOTHING")); }
    public List<OrderSummary> candidateOrders() { return this.<List<OrderSummary>>value("candidateOrders").orElse(List.of()); }
    public boolean resolved()         { return this.<Boolean>value("resolved").orElse(false); }
    public Optional<String> toolResult() { return this.value("toolResult"); }
    public List<ChatMessage> messages()  { return this.<List<ChatMessage>>value("messages").orElse(List.of()); }
}
```

Enums de control (decisiones de flujo **en Java**, no en prompts):

```java
public enum Intent  { TRACKING, CANCEL, RETURN, SUPPORT, TICKETS, FAQ, CHATTY, OTHER, NONE }
public enum Awaiting { NOTHING, ORDER_CHOICE, CANCEL_CONFIRM, RETURN_CONFIRM, TICKET_CONFIRM, TICKET_INFO }
```

`awaiting` es la pieza que implementa el ciclo: indica **qué pregunta dejó pendiente el turno anterior**, para que el router del turno siguiente interprete el mensaje del usuario como respuesta a esa pregunta y no como una petición nueva (aunque también debe detectar cambios de tema: "déjalo, mejor cancela el otro").

> **Serialización:** el checkpointer serializa el estado. Mantener en el estado solo tipos serializables sencillos (String, Long, Boolean, records). Los `ChatMessage` de LangChain4j se guardan vía el `StateSerializer` correspondiente (LangGraph4j trae serializers para mensajes LC4j; ver ejemplos de `llms-lgraph.txt`).

## Construcción del grafo

```java
@Component
@RequiredArgsConstructor
public class SupportAgentGraph {

    private final RouterNode routerNode;
    private final ResolveOrderNode resolveOrderNode;
    private final TrackingNode trackingNode;
    private final CancelCheckNode cancelCheckNode;
    private final CancelExecNode cancelExecNode;
    private final ReturnCheckNode returnCheckNode;
    private final ReturnExecNode returnExecNode;
    private final SupportNode supportNode;
    private final FaqNode faqNode;
    private final FallbackNode fallbackNode;
    private final RespondNode respondNode;

    public StateGraph<SupportState> build() throws GraphStateException {
        return new StateGraph<>(SupportState.SCHEMA, stateSerializer())
            .addNode("router",        node_async(routerNode))
            .addNode("resolve_order", node_async(resolveOrderNode))
            .addNode("tracking",      node_async(trackingNode))
            .addNode("cancel_check",  node_async(cancelCheckNode))
            .addNode("cancel_exec",   node_async(cancelExecNode))
            .addNode("return_check",  node_async(returnCheckNode))
            .addNode("return_exec",   node_async(returnExecNode))
            .addNode("support",       node_async(supportNode))
            .addNode("faq",           node_async(faqNode))
            .addNode("chatty",        node_async(chattyNode))
            .addNode("fallback",      node_async(fallbackNode))
            .addNode("respond",       node_async(respondNode))

            .addEdge(START, "router")

            .addConditionalEdges("router",
                edge_async(this::routeFromRouter),
                Map.of(
                    "resolve_order", "resolve_order",
                    "tracking",      "tracking",
                    "cancel_check",  "cancel_check",
                    "return_check",  "return_check",
                    "support",       "support",
                    "faq",           "faq",
                    "chatty",        "chatty",
                    "fallback",      "fallback"))

            .addConditionalEdges("resolve_order",
                edge_async(this::routeFromResolveOrder),
                Map.of(
                    "tracking",     "tracking",
                    "cancel_check", "cancel_check",
                    "return_check", "return_check",
                    "ask_user",     "respond"))

            .addEdge("tracking", "respond")

            .addConditionalEdges("cancel_check",
                edge_async(this::routeFromCancelCheck),
                Map.of(
                    "execute",  "cancel_exec",
                    "ask_user", "respond"))

            .addConditionalEdges("return_check",
                edge_async(this::routeFromReturnCheck),
                Map.of(
                    "return_exec", "return_exec",
                    "ask_user",    "respond"))

            .addEdge("cancel_exec", "respond")
            .addEdge("return_exec", "respond")
            .addEdge("support",  "respond")
            .addEdge("faq",      "respond")
            .addEdge("fallback", "respond")
            .addEdge("respond", END);
    }

    // ---- Aristas condicionales: Java puro sobre el estado ----

    private String routeFromRouter(SupportState s) {
        return switch (s.intent()) {
            case TRACKING -> s.orderId().isPresent() ? "tracking" : "resolve_order";
            case CANCEL   -> s.orderId().isPresent() ? "cancel_check" : "resolve_order";
            case RETURN   -> s.orderId().isPresent() ? "return_check" : "resolve_order";
            case SUPPORT  -> "support";
            case FAQ      -> "faq";
            case CHATTY   -> "chatty";
            default       -> "fallback";
        };
    }

    private String routeFromResolveOrder(SupportState s) {
        if (s.orderId().isEmpty()) return "ask_user";          // sigue ambiguo → preguntar
        return switch (s.intent()) {
            case CANCEL -> "cancel_check";
            case RETURN -> "return_check";
            default     -> "tracking";
        };
    }

    private String routeFromCancelCheck(SupportState s) {
        // cancel_check deja en el estado si el pedido es cancelable y si el usuario ya confirmó
        boolean confirmed = s.awaiting() == Awaiting.NOTHING; // el nodo limpia awaiting al recibir confirmación
        boolean cancellable = s.<Boolean>value("cancellable").orElse(false);
        return (cancellable && confirmed) ? "execute" : "ask_user";
    }

    private String routeFromReturnCheck(SupportState s) {
        // return_check deja en el estado si el pedido es devolvible y si el usuario ya confirmó
        boolean returnable = s.<Boolean>value("returnable").orElse(false);
        return (returnable && "YES".equals(s.confirmation())) ? "return_exec" : "ask_user";
    }
}
```

Imports estáticos relevantes: `org.bsc.langgraph4j.StateGraph.START`, `...END`, `org.bsc.langgraph4j.action.AsyncNodeAction.node_async`, `org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async`.

## Contrato de los nodos

Cada nodo es una clase Spring (`@Component`) que implementa `NodeAction<SupportState>`: recibe el estado y devuelve **solo las claves que actualiza** (LangGraph4j las fusiona según el `SCHEMA`).

### `router` — clasificación de intención + extracción

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class RouterNode implements NodeAction<SupportState> {

    private final IntentClassifier classifier; // AiService de LangChain4j

    @Override
    public Map<String, Object> apply(SupportState state) {
        log.debug("---ROUTER---");
        String lastUserMessage = lastUserText(state);

        // Si el turno anterior dejó una pregunta pendiente, el router decide:
        // ¿el mensaje responde a la pregunta o cambia de tema?
        RouterDecision d = classifier.classify(
            lastUserMessage,
            state.awaiting().name(),
            state.candidateOrders().toString());

        Map<String, Object> updates = new HashMap<>();
        if (d.intent() != Intent.NONE)  updates.put("intent", d.intent().name());
        if (d.orderId() != null)        updates.put("orderId", d.orderId());
        return updates;
    }
}
```

El clasificador es un **AiService de LangChain4j con salida estructurada** (patrón de `llms-lc4j.txt`):

```java
public interface IntentClassifier {

    @SystemMessage("""
        Eres el router de un agente de soporte de e-commerce.
        Clasifica el mensaje del usuario en una intención: TRACKING, CANCEL, SUPPORT u OTHER.
        Contexto: el agente está esperando '{{awaiting}}'. Pedidos candidatos ya ofrecidos: {{candidates}}.
        Si el usuario está respondiendo a una pregunta pendiente eligiendo un pedido
        (por id o por descripción, p.ej. "el portátil"), mantén la intención previa y
        devuelve el orderId elegido. Si menciona explícitamente un número de pedido, extráelo.
        """)
    @UserMessage("{{message}}")
    RouterDecision classify(@V("message") String message,
                            @V("awaiting") String awaiting,
                            @V("candidates") String candidates);

    record RouterDecision(Intent intent, Long orderId) {}
}
```

> La **resolución de referencia** ("el portátil" → 123) se hace aquí porque el LLM ve los candidatos del estado. `resolve_order` solo se ocupa de buscar candidatos cuando aún no los hay.

#### Foco de pedido pegajoso (el agente no salta de pedido en pedido)

Una vez resuelto el pedido, `orderId` es el **foco** de la conversación y es *pegajoso*: mientras haya foco, todo lo que diga el usuario se interpreta sobre ese pedido —aunque cambie de intención ("no me ha llegado" abre una incidencia del **mismo** pedido, no salta a otro—. El foco solo se mueve en tres casos, todos deterministas (Java, no el prompt):

1. **Elección del menú** — `awaiting == ORDER_CHOICE`: el usuario elige uno de los candidatos ofrecidos.
2. **Cambio explícito** — el usuario nombra **otro** pedido (por número o descripción inequívoca); el router devuelve ese `orderId` y descarta el menú anterior.
3. **Salida explícita del foco** — `navigation == BACK_TO_LIST`, `BACK_TO_MENU` o `BACK`: se suelta el foco (`orderId`, `candidates` y `ticketDraft` a `MARK_FOR_REMOVAL`), pero con destinos distintos.

Dos invariantes lo sostienen: el router recibe el foco actual y, si el usuario no nombra otro pedido, devuelve `orderId = null` (conservar foco); y **los `candidates` se descartan en cuanto una skill resuelve** (tracking, cancelación o ticket), para que el router no pueda "reelegir" del menú anterior por parecido temático. Esto es lo que evitaba el salto observado en la demo (`#789` consultado → "no lo he recibido" → el modelo reelegía `#123` del menú aún presente). Cubierto por `RouterNodeTest` y los escenarios de foco de `SupportAgentFlowTest`.

##### Navegación jerárquica: `BACK_TO_LIST`, `BACK_TO_MENU` y `BACK`

La conversación tiene tres niveles: **Menú principal** (capacidades) → **Listado de pedidos** → **Pedido concreto (foco)**. Hay tres señales de navegación:

- **`BACK_TO_LIST`** (explícito: "mis pedidos", "ver todos", "otro pedido", "enséñame el listado"): el router fuerza `intent=TRACKING` (o `CANCEL` si la gestión en curso era una cancelación), de modo que con `orderId` vacío la arista cae en `resolve_order`, que re-ofrece el listado de pedidos.
- **`BACK_TO_MENU`** (explícito: "menú principal", "volver al inicio", "empezar de nuevo"): el router fuerza `intent=OTHER`, de modo que `fromRouter` cae siempre en `fallback`, que presenta de nuevo las capacidades del asistente (incluida la invitación a hacer preguntas generales tipo FAQ).
- **`BACK`** ("atrás" / "volver" a secas, sin decir destino): significa **subir exactamente un nivel**. El LLM **no** decide el destino; lo resuelve `RouterNode.resolveNavigation(...)` según el nivel real. La clave es distinguir si la conversación está **por debajo del listado** (trabajando un pedido concreto) o **en el propio listado**:
  - **Por debajo del listado** → `BACK_TO_LIST` (se vuelve al listado). Es así cuando hay un pedido en **foco real** (`orderId`) **o** cuando se está iniciando una gestión sobre un pedido elegido del listado cuyo **foco aún no se ha comprometido** (foco diferido): `verify` no lo ascendió —p. ej. devolvió DUDA y mandó a `clarify`— y `proposedOrderId` es efímero, así que `orderId` quedó vacío PERO el listado sigue ahí (`candidates`) y ya no estamos eligiendo (`awaiting != ORDER_CHOICE`).
  - **En el propio listado** (`awaiting == ORDER_CHOICE`, eligiendo todavía) o sin nada → `BACK_TO_MENU` (se sube al menú).

Antes, el LLM decidía el destino de un "atrás" según *foco **o** candidatos presentes*. Eso provocaba el bug reportado: estando **ya en el listado** (hay candidatos pero ningún foco), un "atrás" se clasificaba como `BACK_TO_LIST` y **re-pintaba el mismo listado** en bucle, sin subir nunca al menú. Al delegar el nivel al router (que conoce el estado real con certeza), "atrás" siempre sube un nivel y nunca se queda atascado. Además el LLM ya no tiene que adivinar el destino —solo detectar la intención de volver—, lo que evita el caso en que devolvía `navigation=NONE` y el agente respondía "pero ¿entonces qué quieres?".

El discriminante `awaiting != ORDER_CHOICE` es lo que separa "estar en el listado" de "estar por debajo con el foco diferido sin comprometer", y evita reintroducir aquel bucle: en el listado seguimos subiendo al menú; solo cuando se está iniciando una devolución/cancelación sobre un pedido ya elegido (y `verify` aún no fijó el foco) "atrás" vuelve al listado en lugar de saltarse un nivel hasta el menú —justo el foco que el usuario creía tener—.

> **"volver"/"atrás" a secas NO es navegación.** `BACK_TO_MENU` solo se dispara con peticiones explícitas de reseteo ("menú principal", "volver al inicio", "empezar de nuevo"). Frases como "¿qué puedo hacer?", "ayuda" o un "volver" suelto en mitad de una gestión **no** son navegación: normalmente el usuario pregunta qué puede hacer *con el pedido en cuestión*. (Antes "qué puedo hacer" disparaba `BACK_TO_MENU` y secuestraba la conversación hacia el menú.)

#### Digresión FAQ/CHATTY con retorno (dialog-stack de 1 nivel)

Una consulta lateral —una pregunta general (`FAQ`) o un comentario de charla informal (`CHATTY`)— puede llegar **en mitad de una gestión** con algo pendiente (`awaiting != NOTHING`: eligiendo pedido, confirmando una cancelación o describiendo una incidencia). El patrón estándar es la **digresión con retorno**: se atiende y se retoma la tarea donde estaba.

Se implementa con un canal `suspendedIntent` (pila de un solo nivel; otra digresión encima no apila más):

1. **Suspensión** — el router detecta `FAQ`/`CHATTY` con `awaiting != NOTHING`: guarda la intención de la tarea en `suspendedIntent`, enruta a `faq`/`chatty` y **no toca** `orderId`/`candidates`/`ticketDraft`/`awaiting`. El nodo responde con `resolved=false` y `respond` cierra recordando lo que quedaba pendiente.
2. **Retorno** — al turno siguiente, el router pasa al clasificador el `suspendedIntent` como intención "vigente"; cuando el usuario retoma, el LLM mantiene la gestión —no la digresión— y el router descarta `suspendedIntent`.

Una digresión **sin** gestión pendiente es una consulta normal, no se suspende nada. Cubierto por `RouterNodeTest` y `SupportAgentFlowTest` (`faqDigressionDuringOrderChoiceResumes`, `chattyDoesNotTouchBusiness`).

#### `chatty`: la charla informal no es negocio

`CHATTY` ("¿cómo te llamas?", "¿a qué hora cierras?", bromas) se responde de forma breve y neutra y, deliberadamente, **no toca ningún canal de negocio** (`orderId`, `awaiting`, `ticketDraft`). Como la acumulación del ticket vive solo en `SupportNode` y ningún nodo de negocio lee el `history`, la charla queda como "paja": se conserva en el historial para que la conversación tenga sentido, pero no influye en pedidos ni incidencias. Es la separación que pedía el caso de uso: distinguir la conversación social del estado de la tarea.

#### Ambigüedad con contexto: aclarar, no volver al menú

Antes, todo lo que el modelo no clasificaba con confianza caía en `OTHER → fallback`, que volcaba el menú de capacidades — un imán de "tirar para atrás" ante cualquier mensaje confuso. Ahora, si hay una gestión en curso (foco o `awaiting`) y el turno es ambiguo (`OTHER`), el router cuenta el intento en `clarifyAttempts` y `fallback` entra en **modo aclaración**: en vez del menú, pide concretar sobre el pedido en foco. Si se repite (`clarifyAttempts >= 2`), reorienta y sugiere volver al menú principal. Un turno claro reinicia el contador.

Dos piezas lo sostienen:
- **El foco no se suelta al no poder cancelar.** `cancel_check`, cuando un pedido no es cancelable, ya no borra `orderId`: el usuario sigue "sobre" ese pedido y puede preguntar otras cosas sin re-elegirlo.
- **El pedido en foco viaja como contexto.** `RespondNode` carga el pedido en foco (`OrderTools.findOrder`) y lo pasa al `Responder` como `PEDIDO_EN_FOCO` (dato fiable, no inventado), junto al `TOOL_RESULT` y la pregunta. Así "este pedido", las dudas y las aclaraciones tienen el pedido como contexto.

Cubierto por `SupportAgentFlowTest` (`ambiguousWithFocusClarifiesThenEscalates`, `nonCancellableKeepsFocus`) y `RouterNodeTest` (`clarifyAttemptsTrackOnlyWithContext`).

#### Acciones de la interfaz: atajos deterministas (sin LLM)

No todo lo que llega como "mensaje del usuario" es lenguaje natural. Algunos mensajes son en realidad **acciones de la interfaz** (clic en una tarjeta o en un botón). El router los resuelve en un *fast-path* al inicio de `apply()`, **antes** de llamar al LLM (clase [`UiAction`](../../backend/src/main/java/io/github/mikel0ne/l1copilot/agent/UiAction.java)):

- **Selección de pedido** — al pulsar una tarjeta, la UI envía `#<id>`. Si hay un menú activo (`awaiting=ORDER_CHOICE`) y el id está entre los candidatos, el router fija el foco de forma determinista, conservando la intención (tracking/cancelación) con la que se ofreció el menú. Antes esto dependía de que el LLM extrajera el id; si fallaba, el foco se desmoronaba y la conversación volvía a `resolve_order`.
- **Confirmación de cancelación** — una acción **irreversible** no debe depender de interpretar "sí/cancélalo/confirmo" con NLU (era frágil: el modelo a veces devolvía `NONE` y se entraba en un bucle de "¿me lo confirmas?"). Cuando `cancel_check` necesita confirmación, marca `pendingCancelId`; `ConversationService` lo traduce en un `ConfirmActionDto` (título, aviso de irreversibilidad y dos botones). Cada botón lleva un `payload` (`action:confirm-cancel:<id>` / `action:abort-cancel:<id>`) que el router interpreta como `confirmation=YES/NO` sin tocar el modelo. El clic ES la autorización.
- **Confirmación de devolución** — idéntico mecanismo para la skill `RETURN`. Cuando `return_check` necesita confirmación, marca `pendingReturnId`; `ConversationService` genera el `ConfirmActionDto` con los botones `action:confirm-return:<id>` / `action:abort-return:<id>`, que el router resuelve de forma determinista como `RETURN` + `confirmation=YES/NO`. Misma convención `UiAction` a ambos lados.

Patrón **human-in-the-loop** clásico: la decisión crítica se materializa como una acción explícita del usuario, determinista y testeable, no como una inferencia probabilística. Cubierto por `RouterNodeTest` (comandos y selección) y `SupportAgentFlowTest.cancellationViaButtonIsDeterministic`.

#### Base de conocimiento estática: `faq.md` / `FaqKnowledgeBase`

La skill `faq` (intención `FAQ`: preguntas generales sobre envíos, devoluciones, pagos, garantía... que no son sobre un pedido concreto) no usa tools de dominio ni una llamada extra al LLM. `FaqKnowledgeBase` (`agent/llm`) carga una vez `classpath:faq.md` y `FaqNode` vuelca su contenido íntegro como `toolResult`; `respond` (regla 8) cita solo lo relevante para la pregunta del usuario, sin volcar el documento entero.

### `resolve_order` — desambiguación proactiva

```java
@Override
public Map<String, Object> apply(SupportState state) {
    log.debug("---RESOLVE ORDER---");
    List<OrderSummary> recent = orderTools.getRecentOrders(state.userId());

    if (recent.isEmpty())
        return Map.of("toolResult", "El usuario no tiene pedidos.",
                      "awaiting", Awaiting.NOTHING.name());

    if (recent.size() == 1)                     // único candidato → autoselección
        return Map.of("orderId", recent.get(0).id(),
                      "awaiting", Awaiting.NOTHING.name());

    return Map.of("candidateOrders", recent,    // varios → preguntar (el turno acabará en respond)
                  "awaiting", Awaiting.ORDER_CHOICE.name(),
                  "toolResult", toJson(recent));
}
```

### `tracking`, `cancel_check`, `cancel_exec`, `return_check`, `return_exec`, `support`

Mismo patrón: invocan la tool Java correspondiente, vuelcan el resultado tipado en `toolResult` (y flags como `cancellable`, `returnable`, `resolved`), y actualizan `awaiting`:

- `cancel_check`: si el pedido es cancelable y `awaiting != CANCEL_CONFIRM`, deja `awaiting=CANCEL_CONFIRM` (pedirá confirmación). Si el usuario ya confirmó (el router detectó un "sí" con `awaiting=CANCEL_CONFIRM`), limpia `awaiting` y la arista manda a `cancel_exec`. Si el pedido **ya está entregado**, cancelar no aplica: el nodo instruye a `respond` para que reconduzca hacia una **devolución** (no hacia una incidencia).
- `return_check` / `return_exec`: **réplica exacta del flujo de cancelación, pero para devoluciones** (intención `RETURN`, pedidos en estado `DELIVERED`). `return_check` valida la regla `isReturnable()` en Java; si procede y aún no hay confirmación, marca `pendingReturnId` y deja `awaiting=RETURN_CONFIRM` para que la UI muestre el botón de confirmación. Tras el clic, `return_exec` llama a `requestReturn`, pone el pedido en estado `RETURN_REQUESTED` y emite una **tarjeta-enlace a la etiqueta de devolución** (`returnCards`), igual que `support` emite el enlace al ticket. Si el pedido **ya está en devolución** (`RETURN_REQUESTED`) —el usuario vuelve sobre él o pide "muéstrame la etiqueta"— `return_check` no reinicia nada pero **re-emite la tarjeta-enlace** para que pueda abrir o reimprimir la etiqueta. Una devolución **nunca** se gestiona como incidencia: tiene su propia vía.
- `support`: **nunca crea la incidencia "a lo loco"**; sigue un human-in-the-loop con gate de confirmación, igual que la cancelación. En el primer turno con intención `SUPPORT` no llama a ninguna tool: guarda el mensaje que disparó el tema como semilla en `ticketDraft` y deja `awaiting=TICKET_CONFIRM`, proponiendo abrir la incidencia y pidiendo que el usuario confirme y describa el problema con detalle. Si el usuario sigue adelante, **une** la semilla con lo que cuente ahora; si aún falta detalle deja `awaiting=TICKET_INFO` para ampliarlo, y cuando hay material suficiente llama a `createTicket` y marca `resolved=true`. Si el usuario rechaza la incidencia o pide otra cosa, el **router** descarta el borrador (`ticketDraft`) y reencamina el turno: el nodo `support` ni se entra.

### `respond` — redacción única de la respuesta

```java
@Override
public Map<String, Object> apply(SupportState state) {
    log.debug("---RESPOND---");
    String reply = responder.write(           // AiService: redacta en español, tono cercano,
        state.intent().name(),                // sin inventar datos: SOLO puede usar toolResult
        state.awaiting().name(),
        state.toolResult().orElse("(sin datos)"),
        lastUserText(state));
    return Map.of("messages", List.of(AiMessage.from(reply)));
}
```

Regla del prompt de `respond`: *"Responde únicamente con la información de TOOL_RESULT. Si AWAITING es ORDER_CHOICE, formula la pregunta de elección listando los pedidos. Si es CANCEL_CONFIRM, pide confirmación explícita sí/no. Nunca inventes estados de pedido."*

> **Registro tú/usted (`treatment`).** El asistente unifica el tono de **todos** los mensajes con un registro derivado de la edad del usuario (`TreatmentPolicy`, umbral `app.treatment.formal-age-threshold`, 60 por defecto): por debajo, tuteo simpático; a partir del umbral, trato de usted amable. `ConversationService` lo siembra en el estado cada turno; `RespondNode` lo pasa al `Responder` como `TRATAMIENTO` (regla 0 del prompt, obligatoria y sin mezclar registros). Los pocos textos hardcodeados (p. ej. el título del botón de cancelación, o el saludo del frontend) tienen sus dos variantes. Antes el botón tuteaba y el LLM trataba de usted en la misma pantalla: justo la incoherencia que esto elimina.

> **Semántica de las tarjetas (`cardsShown`).** Una misma señal "hay tarjetas debajo" cubre dos situaciones opuestas, y el prompt debe distinguirlas por `awaiting`, no solo por `cardsShown`:
> - `awaiting = ORDER_CHOICE` → las tarjetas son un **menú para elegir**: el texto pide sobre cuál actuar.
> - cualquier otro `awaiting` (típicamente `NOTHING`, tras `tracking`/`cancel_exec`) → las tarjetas **ilustran el pedido sobre el que ya se informa**: el texto transmite el resultado (estado del envío, cancelación…) y **no** vuelve a pedir que se elija.
>
> Sin esta distinción, al seleccionar un pedido del menú el modelo veía de nuevo una tarjeta "clicable" y re-preguntaba "¿sobre cuál quieres informarte?" en lugar de dar el estado del envío. Los tests de flujo no lo detectaban porque el responder de prueba (`ECHO[toolResult]`) no ejecuta el prompt real; es un riesgo conocido de redacción que vive solo en el prompt.

> **Devolución sin ruido de cancelación.** Cancelación y devolución son vías separadas, y el prompt las cruza **solo en la dirección que el usuario plantea**:
> - Usuario pide **cancelar** un pedido ya entregado → cancelar no aplica; el responder le hace ver que ese procedimiento ya no es posible y le ofrece la **devolución** (regla derivada de `cancel_check`, ver arriba).
> - Usuario pide **devolver** y no ha mencionado la cancelación → el responder va **directo** a la vía de devolución y **no** introduce el tema de la cancelación. Anticipar "ya no se puede cancelar" cuando nadie lo ha pedido solo confunde.
>
> Esta es la **regla simétrica** añadida al system prompt de `Responder`. Importante: el `toolResult` de `return_check` ya era correcto (solo habla de devolución); la fuga venía del prompt, que describía el ciclo del pedido emparejando siempre cancelación y devolución. Encaja con la norma de redacción de prompts (ver `copilot-instructions.md`): se dan **indicaciones** de qué comunicar, no respuestas literales, y no se sacan temas que el usuario no ha planteado.

## Compilación, checkpointer y memoria conversacional

```java
@Configuration
public class AgentConfig {

    @Bean
    public BaseCheckpointSaver checkpointSaver() {
        return new MemorySaver(); // prototipo: en memoria (ADR-6)
    }

    @Bean
    public CompiledGraph<SupportState> supportAgent(SupportAgentGraph builder,
                                                    BaseCheckpointSaver saver) throws GraphStateException {
        return builder.build().compile(
            CompileConfig.builder()
                .checkpointSaver(saver)
                .build());
    }
}
```

Invocación por turno (en `ConversationService`):

```java
public ChatResult chat(String userId, String conversationId, String message) {
    var config = RunnableConfig.builder()
            .threadId(conversationId)          // ← la clave de la memoria entre turnos
            .build();

    var state = supportAgent.invoke(
            Map.of("messages", List.of(UserMessage.from(message)),
                   "userId", userId),
            config)
        .orElseThrow(() -> new IllegalStateException("El grafo no devolvió estado"));

    String reply = lastAiText(state);
    return new ChatResult(reply, state.intent().name(), state.resolved());
}
```

Con el mismo `threadId`, `MemorySaver` restaura `intent`, `candidateOrders`, `awaiting`, etc. — así "El portátil" del segundo turno se entiende sin repetir nada.

## Observabilidad y testing del grafo

- **Logs por nodo** con el patrón `---NOMBRE---` (igual que los ejemplos de `llms-lgraph.txt`); con `dev.langchain4j: DEBUG` ya activado en `application.yml` se ven además las llamadas al modelo.
- **Diagrama generado**: `graph.getGraph(GraphRepresentation.Type.MERMAID)` exporta la topología real; incluirlo en el README del repo y en CI para detectar derivas doc/código.
- **Tests sin LLM**: las aristas (`routeFromRouter`...) son funciones puras sobre `SupportState` → tests unitarios triviales construyendo el estado con `Map.of`. Los nodos de tool se testean con repositorios H2.
- **Tests con LLM falso**: para el flujo completo, sustituir `IntentClassifier`/`Responder` por fakes deterministas (`@MockBean`) y verificar la secuencia de nodos con `graph.stream(...)` inspeccionando `NodeOutput.node()`.
- **Test de integración real** (opcional, etiquetado `@Tag("llm")`): escenario de referencia completo contra Gemini con la API key de desarrollo.
