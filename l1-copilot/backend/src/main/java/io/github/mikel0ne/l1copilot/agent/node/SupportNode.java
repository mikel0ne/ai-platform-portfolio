package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.Intent;
import io.github.mikel0ne.l1copilot.agent.SupportRouting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.Verdict;
import io.github.mikel0ne.l1copilot.agent.llm.TicketWriter;
import io.github.mikel0ne.l1copilot.agent.llm.TicketWriter.TicketDraft;
import io.github.mikel0ne.l1copilot.agent.llm.Verifier;
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

@Component
@RequiredArgsConstructor
@Slf4j
public class SupportNode implements NodeAction<SupportState> {

    static final int MIN_DETAIL_LENGTH = 60;
    private static final int SUBJECT_MAX_LENGTH = 120;
    private static final int DESCRIPTION_MAX_LENGTH = 2000;

    private final TicketTools ticketTools;
    private final TicketWriter ticketWriter;
    private final Verifier verifier;

    @Override
    public Map<String, Object> apply(SupportState state) {
        Awaiting awaiting = state.awaiting();
        String message = state.userMessage().trim();
        log.debug("---SUPPORT--- awaiting={} confirmation={} draftLength={}",
                awaiting, state.confirmation(), state.ticketDraft().length());

        if (awaiting != Awaiting.TICKET_CONFIRM && awaiting != Awaiting.TICKET_INFO
                && state.ticketDraft().isBlank()) {
            String ticketDraft = message;
            String focus = state.orderId().map(id -> "#" + id).orElse("(ninguno)");
            Verifier.Decision decision = verifier.verify(message, Intent.SUPPORT.name(), focus);
            Verdict verdict = Verdict.fromLlm(decision.verdict());
            log.debug("---SUPPORT--- verify verdict={} reason={}", verdict, decision.reason());

            if (!SupportRouting.proceedsAfterVerify(verdict, Intent.SUPPORT)) {
                String clarifyResult = verdict == Verdict.REJECT
                        ? "CLARIFY: el sistema no interpreta esto como una petición de incidencia. "
                          + "Pregunta con naturalidad qué necesita el usuario, ofreciendo las "
                          + "alternativas más probables primero."
                        : "CLARIFY: no está claro si el usuario quiere abrir una incidencia. "
                          + "Pregunta de forma directa y breve sin asumir nada.";
                return Map.of(
                        "ticketDraft", ticketDraft,
                        "toolResult", clarifyResult);
            }

            return Map.of(
                    "ticketDraft", ticketDraft,
                    "awaiting", Awaiting.TICKET_INFO.name(),
                    "toolResult", "El usuario quiere CONFIRMAR que se abra una incidencia. Confirma "
                            + "que vas a gestionarlo y pídele que describa con detalle el problema "
                            + "para poder registrar la incidencia correctamente.");
        }

        if (awaiting == Awaiting.TICKET_CONFIRM) {
            if ("NO".equals(state.confirmation())) {
                Map<String, Object> declined = new HashMap<>();
                declined.put("awaiting", Awaiting.NOTHING.name());
                declined.put("ticketDraft", AgentState.MARK_FOR_REMOVAL);
                declined.put("resolved", true);
                declined.put("toolResult", "El usuario NO quiere abrir la incidencia. Reconduce con "
                        + "naturalidad hacia en qué más puedes ayudarle, sin abrir nada.");
                return declined;
            }

            boolean briefConfirmation = "YES".equals(state.confirmation())
                    && message.length() < MIN_DETAIL_LENGTH;
            if (briefConfirmation) {
                return Map.of(
                        "awaiting", Awaiting.TICKET_INFO.name(),
                        "toolResult", "El usuario confirma que quiere abrir la incidencia. Pídele que "
                                + "describa con detalle qué ha ocurrido para poder abrirla. No "
                                + "inventes datos.");
            }

            return collectOrCreate(state, message);
        }

        String rawDescription = state.ticketDraft().isBlank()
                ? message
                : state.ticketDraft() + "\n" + message;
        return collectOrCreate(state, rawDescription);
    }

    private Map<String, Object> collectOrCreate(SupportState state, String rawDescription) {
        if (rawDescription.strip().length() < MIN_DETAIL_LENGTH) {
            return Map.of(
                    "ticketDraft", rawDescription,
                    "awaiting", Awaiting.TICKET_INFO.name(),
                    "toolResult", "La descripción del problema sigue siendo demasiado breve. "
                            + "Hay que pedir al usuario más detalle para poder abrir la incidencia.");
        }
        return createTicket(state, rawDescription);
    }

    private Map<String, Object> createTicket(SupportState state, String rawDescription) {
        Long orderId = state.orderId().orElse(null);

        TicketDraft draft = writeDraft(rawDescription);

        Map<String, Object> updates = new HashMap<>();
        TicketCreated ticket;
        try {
            ticket = ticketTools.createTicket(state.userId(), orderId, draft.subject(), draft.description());
        } catch (OrderNotFoundException e) {
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

        updates.put("orderId", AgentState.MARK_FOR_REMOVAL);
        updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
        return updates;
    }

    private TicketDraft writeDraft(String rawDescription) {
        try {
            TicketDraft draft = ticketWriter.draft(rawDescription);
            if (draft != null && hasText(draft.subject()) && hasText(draft.description())) {
                return new TicketDraft(
                        clip(draft.subject().trim(), SUBJECT_MAX_LENGTH),
                        clip(draft.description().trim(), DESCRIPTION_MAX_LENGTH));
            }
            log.warn("TicketWriter returned an incomplete draft; using the raw text");
        } catch (RuntimeException e) {
            log.warn("TicketWriter failed to draft the ticket; using the raw text", e);
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
