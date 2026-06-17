package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.Intent;
import io.github.mikel0ne.l1copilot.agent.Navigation;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.UiAction;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier;
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
                describeFocus(state));

        Map<String, Object> updates = new HashMap<>();
        Intent intent = Intent.fromLlm(decision.intent());

        // El usuario pide salir del foco actual: hay dos destinos posibles.
        // BACK_TO_LIST se re-ofrecen los pedidos (forzando TRACKING/CANCEL para que la
        // arista del router caiga en resolve_order al quedar el orderId vacío).
        // BACK_TO_MENU se vuelve al menú de capacidades (intent=OTHER → fallback).
        Navigation navigation = Navigation.fromLlm(decision.navigation());
        if (navigation == Navigation.BACK_TO_LIST || navigation == Navigation.BACK_TO_MENU) {
            updates.put("orderId", AgentState.MARK_FOR_REMOVAL);
            updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
            updates.put("ticketDraft", AgentState.MARK_FOR_REMOVAL);
            updates.put("suspendedIntent", AgentState.MARK_FOR_REMOVAL);
            updates.put("awaiting", Awaiting.NOTHING.name());
            updates.put("clarifyAttempts", 0);

            Intent target = navigation == Navigation.BACK_TO_MENU
                    ? Intent.OTHER
                    : targetForBackToList(intent, state.intent());
            updates.put("intent", target.name());
            log.debug("---ROUTER--- {}: se suelta el foco (intent={})", navigation, target);
            return updates;
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
            updates.put("orderId", decision.orderId());
            if (state.awaiting() == Awaiting.ORDER_CHOICE) {
                updates.put("awaiting", Awaiting.NOTHING.name());
            } else if (state.orderId().filter(decision.orderId()::equals).isEmpty()) {
                // Cambio explícito a otro pedido: cualquier menú anterior ya no aplica.
                updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
            }
        }

        // La confirmación solo se honra si realmente había una pendiente:
        // un "sí" suelto en el primer turno jamás dispara una cancelación.
        if (state.awaiting() == Awaiting.CANCEL_CONFIRM) {
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

    /** Hay una gestión "viva" sobre la que el usuario podría estar hablando. */
    private boolean hasOngoingContext(SupportState state) {
        return state.orderId().isPresent() || state.awaiting() != Awaiting.NOTHING;
    }

    /** Consultas laterales de un solo turno que no son gestiones: se atienden y se retoma. */
    private boolean isDigression(Intent intent) {
        return intent == Intent.FAQ || intent == Intent.CHATTY;
    }

    /**
     * Intención con la que reencaminar al listado tras un BACK_TO_LIST: si este turno
     * trae una skill clara (TRACKING/CANCEL) se usa; si no (típico "ver mis pedidos" →
     * OTHER), se respeta la gestión vigente, y en último caso TRACKING (solo listar).
     * Con el orderId ya vacío, la arista del router cae en resolve_order.
     */
    private Intent targetForBackToList(Intent turnIntent, Intent currentIntent) {
        if (turnIntent == Intent.TRACKING || turnIntent == Intent.CANCEL) {
            return turnIntent;
        }
        return currentIntent == Intent.CANCEL ? Intent.CANCEL : Intent.TRACKING;
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
