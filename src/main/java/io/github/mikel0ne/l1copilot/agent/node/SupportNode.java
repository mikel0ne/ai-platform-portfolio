package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.dto.tools.TicketCreated;
import io.github.mikel0ne.l1copilot.tools.OrderNotFoundException;
import io.github.mikel0ne.l1copilot.tools.TicketTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Skill SUPPORT: recopila la información mínima de la incidencia (en uno o
 * varios turnos) y crea el ticket para un agente humano.
 * <p>
 * Heurística deliberadamente determinista: si el primer mensaje ya trae una
 * descripción razonable (≥ {@value #MIN_DETAIL_LENGTH} caracteres) se crea el
 * ticket directamente; si es escueto ("no funciona") se pide detalle una vez
 * y se acumula en {@code ticketDraft}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SupportNode implements NodeAction<SupportState> {

    static final int MIN_DETAIL_LENGTH = 60;
    private static final int SUBJECT_MAX_LENGTH = 120;

    private final TicketTools ticketTools;

    @Override
    public Map<String, Object> apply(SupportState state) {
        log.debug("---SUPPORT--- awaiting={} draftLength={}", state.awaiting(), state.ticketDraft().length());

        boolean collecting = state.awaiting() == Awaiting.TICKET_INFO || !state.ticketDraft().isBlank();
        String message = state.userMessage().trim();

        if (!collecting && message.length() < MIN_DETAIL_LENGTH) {
            return Map.of(
                    "ticketDraft", message,
                    "awaiting", Awaiting.TICKET_INFO.name(),
                    "toolResult", "La descripción del problema es demasiado breve. "
                            + "Hay que pedir al usuario más detalle para abrir la incidencia.");
        }

        String description = state.ticketDraft().isBlank()
                ? message
                : state.ticketDraft() + "\n" + message;
        Long orderId = state.orderId().orElse(null);

        Map<String, Object> updates = new HashMap<>();
        TicketCreated ticket;
        try {
            ticket = ticketTools.createTicket(state.userId(), orderId, subjectFrom(description), description);
        } catch (OrderNotFoundException e) {
            // El pedido en contexto no era del usuario: se abre el ticket sin pedido asociado
            ticket = ticketTools.createTicket(state.userId(), null, subjectFrom(description), description);
        }

        updates.put("toolResult", "Ticket de soporte #" + ticket.ticketId() + " creado ('"
                + ticket.subject() + "', estado " + ticket.status()
                + "). Un agente humano contactará con el usuario.");
        updates.put("resolved", true);
        updates.put("awaiting", Awaiting.NOTHING.name());
        updates.put("ticketDraft", "");
        return updates;
    }

    private String subjectFrom(String description) {
        String firstLine = description.lines().findFirst().orElse("Incidencia").trim();
        return firstLine.length() <= SUBJECT_MAX_LENGTH
                ? firstLine
                : firstLine.substring(0, SUBJECT_MAX_LENGTH);
    }
}
