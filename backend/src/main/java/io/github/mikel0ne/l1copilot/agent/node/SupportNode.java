package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.llm.TicketWriter;
import io.github.mikel0ne.l1copilot.agent.llm.TicketWriter.TicketDraft;
import io.github.mikel0ne.l1copilot.dto.TicketCardDto;
import io.github.mikel0ne.l1copilot.dto.tools.TicketCreated;
import io.github.mikel0ne.l1copilot.tools.OrderNotFoundException;
import io.github.mikel0ne.l1copilot.tools.TicketTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill SUPPORT: abre una incidencia para un agente humano, pero NUNCA "a lo
 * loco". Crear un ticket es una acción real, así que sigue un human-in-the-loop
 * con gate de confirmación, igual que la cancelación:
 * <ol>
 *   <li><b>Propuesta</b> (primer turno con intención SUPPORT): no se crea nada.
 *   Se guarda el mensaje que disparó el tema como semilla de la incidencia
 *   ({@code ticketDraft}) y se pasa a {@link Awaiting#TICKET_CONFIRM}, pidiendo
 *   al usuario que confirme que quiere abrir la incidencia y describa con detalle
 *   qué ha ocurrido.</li>
 *   <li><b>Recogida</b>: el usuario sigue adelante (el router mantiene SUPPORT).
 *   Se UNE la semilla con lo que cuente ahora. Si entre ambos no hay detalle
 *   suficiente (&lt; {@value #MIN_DETAIL_LENGTH} caracteres) se pide ampliar una
 *   vez más ({@link Awaiting#TICKET_INFO}); si lo hay, se crea el ticket.</li>
 * </ol>
 * Si el usuario rechaza la incidencia o pide otra cosa en el gate, el router se
 * encarga de descartar el borrador y reencaminar el turno: este nodo ni se entra.
 * <p>
 * El asunto y la descripción del ticket no son el texto en bruto del usuario:
 * un {@link TicketWriter} (LLM) los reescribe de forma limpia y profesional
 * antes de persistirlos. Si esa redacción falla, se cae con elegancia al texto
 * original para no perder la incidencia.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SupportNode implements NodeAction<SupportState> {

    static final int MIN_DETAIL_LENGTH = 60;
    private static final int SUBJECT_MAX_LENGTH = 120;
    private static final int DESCRIPTION_MAX_LENGTH = 2000;

    private final TicketTools ticketTools;
    private final TicketWriter ticketWriter;

    @Override
    public Map<String, Object> apply(SupportState state) {
        log.debug("---SUPPORT--- awaiting={} draftLength={}", state.awaiting(), state.ticketDraft().length());

        String message = state.userMessage().trim();
        boolean atGate = state.awaiting() == Awaiting.TICKET_CONFIRM
                || state.awaiting() == Awaiting.TICKET_INFO
                || !state.ticketDraft().isBlank();

        // FASE 1 — Propuesta: primer turno con intención de incidencia. No se crea nada:
        // se guarda el mensaje que disparó el tema como semilla y se pide confirmación
        // explícita + detalle. Así no se abre una incidencia por cada frase del usuario.
        if (!atGate) {
            return Map.of(
                    "ticketDraft", message,
                    "awaiting", Awaiting.TICKET_CONFIRM.name(),
                    "toolResult", "El usuario parece reportar un problema, pero AÚN NO se ha "
                            + "abierto ninguna incidencia. Hay que CONFIRMAR con él si quiere que se "
                            + "abra una incidencia para que la revise un agente humano y pedirle que "
                            + "describa con detalle qué ha ocurrido.");
        }

        // FASE 2 — Recogida: el usuario sigue adelante. Se UNE el mensaje que activó la
        // incidencia con el detalle que da ahora; si entre ambos no hay material suficiente,
        // se pide ampliar una vez más antes de crear nada.
        String rawDescription = state.ticketDraft().isBlank()
                ? message
                : state.ticketDraft() + "\n" + message;

        if (rawDescription.strip().length() < MIN_DETAIL_LENGTH) {
            return Map.of(
                    "ticketDraft", rawDescription,
                    "awaiting", Awaiting.TICKET_INFO.name(),
                    "toolResult", "La descripción del problema sigue siendo demasiado breve. "
                            + "Hay que pedir al usuario más detalle para poder abrir la incidencia.");
        }

        Long orderId = state.orderId().orElse(null);

        // El LLM reescribe el texto en bruto del usuario en un asunto y descripción limpios.
        TicketDraft draft = writeDraft(rawDescription);

        Map<String, Object> updates = new HashMap<>();
        TicketCreated ticket;
        try {
            ticket = ticketTools.createTicket(state.userId(), orderId, draft.subject(), draft.description());
        } catch (OrderNotFoundException e) {
            // El pedido en contexto no era del usuario: se abre el ticket sin pedido asociado
            ticket = ticketTools.createTicket(state.userId(), null, draft.subject(), draft.description());
        }

        String code = TicketCardDto.code(ticket.ticketId());
        Map<String, Object> card = new HashMap<>();
        card.put("id", ticket.ticketId());
        card.put("code", code);
        card.put("title", ticket.subject());
        card.put("status", ticket.status());
        card.put("createdAt", ticket.createdAt().toString());

        updates.put("toolResult", String.format(
                "Ticket de soporte %s creado ('%s', estado %s). Un agente humano contactará con el "
                + "usuario. La interfaz muestra debajo un enlace al ticket que el usuario puede "
                + "abrir para ver el detalle.", code, ticket.subject(), ticket.status()));
        updates.put("ticketCards", List.of(card));
        updates.put("resolved", true);
        updates.put("awaiting", Awaiting.NOTHING.name());
        updates.put("ticketDraft", "");
        // Gestión cerrada: se suelta el foco y el menú para que el siguiente turno
        // empiece limpio (mismo criterio que la cancelación).
        updates.put("orderId", AgentState.MARK_FOR_REMOVAL);
        updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
        return updates;
    }

    /**
     * Pide al LLM un asunto y una descripción limpios. Si la redacción falla o
     * devuelve campos vacíos, cae al texto en bruto para no perder la incidencia.
     */
    private TicketDraft writeDraft(String rawDescription) {
        try {
            TicketDraft draft = ticketWriter.draft(rawDescription);
            if (draft != null && hasText(draft.subject()) && hasText(draft.description())) {
                return new TicketDraft(
                        clip(draft.subject().trim(), SUBJECT_MAX_LENGTH),
                        clip(draft.description().trim(), DESCRIPTION_MAX_LENGTH));
            }
            log.warn("TicketWriter devolvió un borrador incompleto; se usa el texto en bruto");
        } catch (RuntimeException e) {
            log.warn("TicketWriter falló al redactar el ticket; se usa el texto en bruto", e);
        }
        return new TicketDraft(subjectFrom(rawDescription), clip(rawDescription, DESCRIPTION_MAX_LENGTH));
    }

    private String subjectFrom(String description) {
        String firstLine = description.lines().findFirst().orElse("Incidencia").trim();
        return clip(firstLine, SUBJECT_MAX_LENGTH);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
