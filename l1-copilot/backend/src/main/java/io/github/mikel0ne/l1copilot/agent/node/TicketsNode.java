package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.dto.TicketCardDto;
import io.github.mikel0ne.l1copilot.dto.tools.TicketSummary;
import io.github.mikel0ne.l1copilot.tools.TicketTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Skill TICKETS: consulta de SOLO LECTURA de las incidencias (tickets de
 * soporte) que el usuario ya tiene abiertas. Es análoga a la consulta de envíos
 * (TRACKING): no abre ni modifica nada, solo informa del estado actual.
 * <p>
 * Importante: una vez creada, la incidencia queda en manos del personal humano
 * de soporte. El asistente puede mostrarla y decir en qué estado está, pero NO
 * puede acelerarla, cerrarla ni gestionarla; eso queda fuera de su alcance.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TicketsNode implements NodeAction<SupportState> {

    private final TicketTools ticketTools;

    @Override
    public Map<String, Object> apply(SupportState state) {
        List<TicketSummary> tickets = ticketTools.listTickets(state.userId());
        log.debug("---TICKETS--- {} incidencia(s)", tickets.size());

        if (tickets.isEmpty()) {
            return Map.of(
                    "toolResult", "El usuario no tiene ninguna incidencia (ticket de soporte) abierta. "
                            + "Si tiene un problema concreto, se le puede ofrecer abrir una nueva incidencia "
                            + "para que la revise un agente humano.",
                    "resolved", true,
                    "awaiting", Awaiting.NOTHING.name());
        }

        List<Map<String, Object>> cards = tickets.stream()
                .map(TicketsNode::card)
                .toList();

        String summary = tickets.stream()
                .map(t -> String.format("%s ('%s', estado %s)",
                        TicketCardDto.code(t.ticketId()), t.subject(), t.status()))
                .collect(Collectors.joining("; "));

        return Map.of(
                "toolResult", "Incidencias del usuario (de la más reciente a la más antigua): " + summary
                        + ". Estas incidencias ya están en manos del equipo humano de soporte: solo se puede "
                        + "informar de su estado actual, no acelerarlas, cerrarlas ni gestionarlas.",
                "ticketCards", cards,
                "resolved", true,
                "awaiting", Awaiting.NOTHING.name());
    }

    private static Map<String, Object> card(TicketSummary t) {
        Map<String, Object> card = new HashMap<>();
        card.put("id", t.ticketId());
        card.put("code", TicketCardDto.code(t.ticketId()));
        card.put("title", t.subject());
        card.put("status", t.status());
        card.put("createdAt", t.createdAt().toString());
        return card;
    }
}

