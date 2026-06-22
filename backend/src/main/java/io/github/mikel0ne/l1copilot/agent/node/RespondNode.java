package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Intent;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.llm.Responder;
import io.github.mikel0ne.l1copilot.config.ConversationHistoryProperties;
import io.github.mikel0ne.l1copilot.config.qualifier.ChattyResponder;
import io.github.mikel0ne.l1copilot.tools.OrderTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Único nodo que redacta texto para el usuario. Centraliza tono e idioma y
 * aplica la regla de oro: solo puede usar la información de {@code toolResult}.
 * Además aporta el pedido en foco como contexto, para resolver referencias
 * ("este pedido") y responder dudas sin que el modelo invente datos.
 * <p>
 * Elige el redactor según la naturaleza del turno: para la charla informal
 * (intención {@code CHATTY}) usa {@code chattyResponder} (modelo de mayor
 * temperatura, respuestas más cálidas); para todo lo demás, el {@code responder}
 * de negocio (temperatura media, más sobrio).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RespondNode implements NodeAction<SupportState> {

    private final Responder responder;

    @ChattyResponder
    private final Responder chattyResponder;

    private final OrderTools orderTools;
    private final ConversationHistoryProperties history;

    @Override
    public Map<String, Object> apply(SupportState state) {
        log.debug("---RESPOND--- intent={} awaiting={}", state.intent(), state.awaiting());

        Responder writer = state.intent() == Intent.CHATTY ? chattyResponder : responder;

        String reply = writer.write(
                state.intent().name(),
                state.awaiting().name(),
                state.toolResult().isBlank() ? "(sin datos)" : state.toolResult(),
                state.userMessage(),
                !state.orderCards().isEmpty() || !state.ticketCards().isEmpty(),
                describeFocusOrder(state),
                state.treatment().name(),
                state.tentative(),
                history.responder().render(state.history()));

        return Map.of(
                "reply", reply,
                "history", List.of("AGENT: " + reply));
    }

    /** Pedido en foco como dato fiable del sistema (no inventado), o "(ninguno)". */
    private String describeFocusOrder(SupportState state) {
        return state.orderId()
                .flatMap(id -> orderTools.findOrder(id, state.userId()))
                .map(o -> "#" + o.id() + " '" + o.description() + "' (" + o.status() + ")")
                .orElse("(ninguno)");
    }
}
