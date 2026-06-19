package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.Intent;
import io.github.mikel0ne.l1copilot.agent.Navigation;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.UiAction;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier;
import io.github.mikel0ne.l1copilot.config.ConversationHistoryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Nodo router: clasifica la intención del mensaje, extrae el pedido referido
 * (incluida la resolución de referencias sobre los candidatos ya ofrecidos:
 * "el portátil" → #123) y detecta confirmaciones pendientes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RouterNode implements NodeAction<SupportState> {

    private static final Set<String> CONFIRMATIONS = Set.of("YES", "NO");

    private final IntentClassifier classifier;
    private final ConversationHistoryProperties history;

    @Override
    public Map<String, Object> apply(SupportState state) {
        log.debug("---ROUTER--- awaiting={} intent={} suspended={}",
                state.awaiting(), state.intent(), state.suspendedIntent().orElse(null));

        // Atajo determinista: los botones de la UI y la selección de una tarjeta llegan
        // como comandos inequívocos. Se resuelven aquí, sin consultar al LLM, para que
        // una acción crítica (confirmar una cancelación) o el foco de un pedido no dependan
        // de que el modelo interprete bien el lenguaje natural.
        Optional<Map<String, Object>> shortcut = deterministicShortcut(state);
        if (shortcut.isPresent()) {
            return shortcut.get();
        }

        // Si hay una gestión suspendida por una digresión FAQ, es ESA la intención
        // que el clasificador debe ver como "vigente": así, cuando el usuario retoma
        // (elige pedido, confirma...), el LLM mantiene la gestión y no la FAQ.
        Intent effectiveCurrent = state.suspendedIntent().orElse(state.intent());

        IntentClassifier.RouterDecision decision = classifier.classify(
                state.userMessage(),
                effectiveCurrent.name(),
                state.awaiting().name(),
                describeCandidates(state),
                describeFocus(state),
                history.classifier().render(state.history()));

        Map<String, Object> updates = new HashMap<>();
        Intent intent = Intent.fromLlm(decision.intent());

        // El usuario pide salir del foco actual. Hay tres señales posibles del clasificador:
        // BACK_TO_LIST / BACK_TO_MENU (destino explícito) y BACK ("atrás/volver" a secas, sin
        // destino). El "atrás" ambiguo lo resolvemos AQUÍ —no el LLM— según el nivel real de la
        // conversación, de modo que siempre suba EXACTAMENTE un nivel (pedido→listado, listado→menú)
        // y nunca se quede re-listando el mismo sitio.
        Navigation navigation = Navigation.fromLlm(decision.navigation());
        if (navigation != Navigation.NONE) {
            Navigation destination = resolveNavigation(navigation, state);
            log.debug("---ROUTER--- navegación {} → {}: se suelta el foco", navigation, destination);
            return navigationUpdates(destination, intent, state.intent());
        }

        // DIGRESIÓN: el usuario hace una consulta lateral (FAQ o charla informal) sin haber
        // cerrado la gestión en curso (hay algo pendiente). Se atiende pero NO se destruye el
        // contexto de la tarea: se recuerda su intención para retomarla después.
        if (isDigression(intent) && state.awaiting() != Awaiting.NOTHING) {
            Intent suspended = state.suspendedIntent().orElse(state.intent());
            updates.put("intent", intent.name());
            updates.put("suspendedIntent", suspended.name());
            updates.put("clarifyAttempts", 0);
            log.debug("---ROUTER--- digresión {}: se suspende la gestión {} (awaiting={})",
                    intent, suspended, state.awaiting());
            return updates;
        }

        // RETORNO: había una gestión suspendida y el usuario ya no hace una consulta lateral
        // (la retoma o cambia de tema). El intent que devolvió el LLM ya es el correcto
        // —vio la gestión como vigente—; solo hay que descartar la suspensión.
        if (state.suspendedIntent().isPresent() && !isDigression(intent)) {
            updates.put("suspendedIntent", AgentState.MARK_FOR_REMOVAL);
            log.debug("---ROUTER--- fin de digresión: se retoma la gestión (intent={})", intent);
        }

        if (intent != Intent.NONE) {
            updates.put("intent", intent.name());
        }

        // GATE DE INCIDENCIA: se había propuesto abrir una incidencia (awaiting
        // TICKET_CONFIRM/TICKET_INFO) y el usuario, en vez de seguir adelante, pide otra cosa
        // —su intención ya no es SUPPORT (las digresiones FAQ/CHATTY se resolvieron arriba)—.
        // Se descarta el borrador a medio hacer y se reencamina el turno a lo que realmente
        // quiere (el foco y el nuevo pedido se resuelven más abajo). Nunca se abre una
        // incidencia no pedida.
        boolean abandoningTicketGate = (state.awaiting() == Awaiting.TICKET_CONFIRM
                || state.awaiting() == Awaiting.TICKET_INFO)
                && intent != Intent.SUPPORT && intent != Intent.NONE;
        if (abandoningTicketGate) {
            updates.put("ticketDraft", AgentState.MARK_FOR_REMOVAL);
            updates.put("awaiting", Awaiting.NOTHING.name());
            log.debug("---ROUTER--- incidencia descartada: el usuario pide otra cosa (intent={})", intent);
        }

        // Foco pegajoso: solo se cambia el pedido en curso si el usuario está eligiendo
        // del menú (ORDER_CHOICE) o nombra explícitamente otro distinto (orderId no nulo).
        // Un orderId nulo conserva el foco; nunca se salta de pedido "a lo loco".
        if (decision.orderId() != null && decision.orderId() > 0) {
            long newFocus = decision.orderId();
            boolean selecting = state.awaiting() == Awaiting.ORDER_CHOICE;
            boolean differentOrder = state.orderId().filter(id -> id == newFocus).isEmpty();
            if (selecting) {
                updates.put("awaiting", Awaiting.NOTHING.name());
            }

            // No comprometer estado antes de validar: si este turno va a pasar por verify, el
            // nuevo foco NO se fija todavía; se deja PROPUESTO y verify lo asciende a foco real
            // solo si aprueba la acción. Si verify lo rechaza (→ clarify) el foco ni se tocó, así
            // nunca cambiamos de pedido para una acción que después no se ejecuta. En cambio, las
            // gestiones a mitad de un gate (que no pasan por verify) sí fijan el foco aquí.
            if (routesToVerify(intent, updates, state)) {
                updates.put("proposedOrderId", newFocus);
            } else {
                updates.put("orderId", newFocus);
                if (!selecting && differentOrder) {
                    // Cambio explícito a otro pedido: cualquier menú anterior ya no aplica.
                    updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
                }
            }
        }

        // La confirmación solo se honra si realmente había una pendiente:
        // un "sí" suelto en el primer turno jamás dispara una cancelación o una devolución.
        if (state.awaiting() == Awaiting.CANCEL_CONFIRM || state.awaiting() == Awaiting.RETURN_CONFIRM) {
            String confirmation = decision.confirmation() == null
                    ? "NONE" : decision.confirmation().trim().toUpperCase();
            updates.put("confirmation", CONFIRMATIONS.contains(confirmation) ? confirmation : "NONE");
        }

        // Modo aclaración: si el mensaje es ambiguo (OTHER) pero hay una gestión en curso,
        // no se vuelca el menú; se cuenta el intento para escalar si se repite. Un turno claro
        // (cualquier otra intención) —o abandonar el gate de incidencia— reinicia el contador.
        if (intent == Intent.OTHER && hasOngoingContext(state) && !abandoningTicketGate) {
            updates.put("clarifyAttempts", state.clarifyAttempts() + 1);
        } else {
            updates.put("clarifyAttempts", 0);
        }

        log.debug("---ROUTER--- decision: intent={} orderId={} confirmation={}",
                decision.intent(), decision.orderId(), decision.confirmation());
        return updates;
    }

    /**
     * Resuelve, sin LLM, los mensajes que en realidad son acciones de la interfaz:
     * confirmar/descartar una cancelación (botón) o elegir un pedido del menú (tarjeta).
     * Devuelve vacío si el mensaje es texto libre que debe clasificar el modelo.
     */
    private Optional<Map<String, Object>> deterministicShortcut(SupportState state) {
        String message = state.userMessage();

        // Botón de confirmación de cancelación: el clic ES la autorización explícita,
        // no hay que interpretar "sí/cancélalo/confirmo" con el modelo.
        Optional<Long> confirmId = UiAction.orderIdOf(message, UiAction.CONFIRM_CANCEL);
        if (confirmId.isPresent()) {
            log.debug("---ROUTER--- comando: confirmar cancelación #{}", confirmId.get());
            return Optional.of(Map.of(
                    "intent", Intent.CANCEL.name(),
                    "orderId", confirmId.get(),
                    "confirmation", "YES",
                    "clarifyAttempts", 0));
        }
        Optional<Long> abortId = UiAction.orderIdOf(message, UiAction.ABORT_CANCEL);
        if (abortId.isPresent()) {
            log.debug("---ROUTER--- comando: descartar cancelación #{}", abortId.get());
            return Optional.of(Map.of(
                    "intent", Intent.CANCEL.name(),
                    "orderId", abortId.get(),
                    "confirmation", "NO",
                    "clarifyAttempts", 0));
        }

        // Botón de confirmación de devolución: el clic ES la autorización explícita.
        Optional<Long> confirmReturnId = UiAction.orderIdOf(message, UiAction.CONFIRM_RETURN);
        if (confirmReturnId.isPresent()) {
            log.debug("---ROUTER--- comando: confirmar devolución #{}", confirmReturnId.get());
            return Optional.of(Map.of(
                    "intent", Intent.RETURN.name(),
                    "orderId", confirmReturnId.get(),
                    "confirmation", "YES",
                    "clarifyAttempts", 0));
        }
        Optional<Long> abortReturnId = UiAction.orderIdOf(message, UiAction.ABORT_RETURN);
        if (abortReturnId.isPresent()) {
            log.debug("---ROUTER--- comando: descartar devolución #{}", abortReturnId.get());
            return Optional.of(Map.of(
                    "intent", Intent.RETURN.name(),
                    "orderId", abortReturnId.get(),
                    "confirmation", "NO",
                    "clarifyAttempts", 0));
        }

        // Selección de un pedido del menú por su tarjeta ("#id"): se fija el foco sin LLM,
        // conservando la intención (tracking/cancelación) con la que se ofreció el menú.
        if (state.awaiting() == Awaiting.ORDER_CHOICE) {
            Optional<Long> picked = UiAction.bareOrderId(message).filter(id -> isCandidate(state, id));
            if (picked.isPresent()) {
                log.debug("---ROUTER--- selección de tarjeta: #{}", picked.get());
                return Optional.of(Map.of(
                        "orderId", picked.get(),
                        "awaiting", Awaiting.NOTHING.name(),
                        "clarifyAttempts", 0));
            }
        }
        return Optional.empty();
    }

    /** ¿El id corresponde a uno de los pedidos ofrecidos en el menú actual? */
    private boolean isCandidate(SupportState state, long orderId) {
        String prefix = "#" + orderId + " ";
        return state.candidates().stream().anyMatch(c -> c.startsWith(prefix));
    }

    /**
     * ¿Este turno se enrutará a verify (validación) con la decisión ya construida en
     * {@code updates}? Replica el criterio de {@link io.github.mikel0ne.l1copilot.agent.SupportRouting}
     * (gestión fresca: TRACKING/CANCEL/SUPPORT sin pregunta pendiente ni incidencia en curso),
     * para decidir si el nuevo foco debe PROPONERSE (lo confirma verify) o fijarse ya.
     */
    private boolean routesToVerify(Intent intent, Map<String, Object> updates, SupportState state) {
        boolean verifiable = intent == Intent.TRACKING || intent == Intent.CANCEL
                || intent == Intent.RETURN || intent == Intent.SUPPORT;
        return verifiable && effectiveAwaiting(updates, state) == Awaiting.NOTHING && effectiveTicketBlank(updates, state);
    }

    /** Awaiting que quedará en efecto: el que el router acaba de poner o, si no, el del estado. */
    private Awaiting effectiveAwaiting(Map<String, Object> updates, SupportState state) {
        Object pending = updates.get("awaiting");
        return pending instanceof String s ? Awaiting.fromState(s) : state.awaiting();
    }

    /** ¿Quedará vacío el borrador de incidencia? (el router solo puede descartarlo, nunca llenarlo). */
    private boolean effectiveTicketBlank(Map<String, Object> updates, SupportState state) {
        return updates.get("ticketDraft") == AgentState.MARK_FOR_REMOVAL || state.ticketDraft().isBlank();
    }

    /** Hay una gestión "viva" sobre la que el usuario podría estar hablando. */
    private boolean hasOngoingContext(SupportState state) {
        return state.orderId().isPresent() || state.awaiting() != Awaiting.NOTHING;
    }

    /** Consultas laterales de un solo turno que no son gestiones: se atienden y se retoma. */
    private boolean isDigression(Intent intent) {
        return intent == Intent.FAQ || intent == Intent.CHATTY;
    }

    /**
     * Resuelve la navegación al destino real según el nivel jerárquico de la conversación
     * (Menú → Listado → Pedido en foco). Los destinos explícitos ({@code BACK_TO_LIST} /
     * {@code BACK_TO_MENU}) se respetan tal cual: si el usuario dice "mis pedidos" o "menú
     * principal" sabe a dónde quiere ir. El "atrás/volver" a secas ({@code BACK}) sube
     * EXACTAMENTE un nivel: desde un pedido en foco se vuelve al listado; estando ya en el
     * listado —o sin foco alguno— se vuelve al menú. Así "atrás" en el listado NO re-lista
     * lo mismo (el bucle que veía el usuario), sino que sube al menú.
     */
    private Navigation resolveNavigation(Navigation navigation, SupportState state) {
        if (navigation != Navigation.BACK) {
            return navigation;
        }
        return state.orderId().isPresent() ? Navigation.BACK_TO_LIST : Navigation.BACK_TO_MENU;
    }

    /**
     * Intención con la que reencaminar al listado tras un BACK_TO_LIST: si este turno
     * trae una skill clara (TRACKING/CANCEL) se usa; si no (típico "ver mis pedidos" →
     * OTHER), se respeta la gestión vigente, y en último caso TRACKING (solo listar).
     * Con el orderId ya vacío, la arista del router cae en resolve_order.
     */
    private Intent targetForBackToList(Intent turnIntent, Intent currentIntent) {
        if (turnIntent == Intent.TRACKING || turnIntent == Intent.CANCEL || turnIntent == Intent.RETURN) {
            return turnIntent;
        }
        if (currentIntent == Intent.CANCEL || currentIntent == Intent.RETURN) {
            return currentIntent;
        }
        return Intent.TRACKING;
    }

    /**
     * Updates comunes a las dos salidas del foco (BACK_TO_LIST / BACK_TO_MENU): se suelta
     * el pedido en foco, el menú y cualquier borrador/suspensión, y se fija la intención
     * destino. BACK_TO_MENU cae siempre en OTHER (→ fallback); BACK_TO_LIST fuerza
     * TRACKING/CANCEL para que, con el orderId ya vacío, la arista caiga en resolve_order.
     */
    private Map<String, Object> navigationUpdates(Navigation navigation, Intent turnIntent, Intent currentIntent) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("orderId", AgentState.MARK_FOR_REMOVAL);
        updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
        updates.put("ticketDraft", AgentState.MARK_FOR_REMOVAL);
        updates.put("suspendedIntent", AgentState.MARK_FOR_REMOVAL);
        updates.put("awaiting", Awaiting.NOTHING.name());
        updates.put("clarifyAttempts", 0);
        // La navegación es una orden determinista del usuario, no una acción de negocio fresca:
        // se marca el turno para que la arista del router NO la mande a verify (que re-leería el
        // "volver atrás" como una consulta y la rechazaría, mandándola a clarify en bucle).
        updates.put("navigating", true);

        Intent target = navigation == Navigation.BACK_TO_MENU
                ? Intent.OTHER
                : targetForBackToList(turnIntent, currentIntent);
        updates.put("intent", target.name());
        return updates;
    }

    private String describeCandidates(SupportState state) {
        if (state.candidates().isEmpty()) {
            return "(ninguno)";
        }
        return String.join("; ", state.candidates());
    }

    private String describeFocus(SupportState state) {
        return state.orderId().map(id -> "#" + id).orElse("(ninguno)");
    }
}
