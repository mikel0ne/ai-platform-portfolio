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

/**
 * Skill SUPPORT: abre una incidencia para un agente humano, pero NUNCA "a lo
 * loco". Crear un ticket es una acción real, así que sigue un human-in-the-loop
 * con un gate de confirmación de TRES pasos limpios y separados. El pedido (si la
 * incidencia es sobre uno) ya se identificó antes en {@code resolve_order}; aquí
 * SUPPORT no pasa por verify: este gate ES su salvaguarda.
 * <ol>
 *   <li><b>Propuesta</b> (entrada fresca): no se crea nada y NO se siembra el
 *   borrador (el mensaje de entrada es una selección de pedido o un disparo vago,
 *   no la descripción). Se pasa a {@link Awaiting#TICKET_CONFIRM} pidiendo SOLO
 *   confirmación (sí/no) de abrir la incidencia, referida al pedido en foco.</li>
 *   <li><b>Confirmación</b> ({@link Awaiting#TICKET_CONFIRM}): si confirma de forma
 *   escueta ("sí"), se pasa a {@link Awaiting#TICKET_INFO} y se le pide la
 *   descripción. Si en vez de un simple "sí" ya cuenta el problema, se trata como
 *   confirmar+describir en uno.</li>
 *   <li><b>Descripción</b> ({@link Awaiting#TICKET_INFO}): se acumula SOLO el
 *   detalle real; si aún es corto (&lt; {@value #MIN_DETAIL_LENGTH} caracteres) se
 *   pide ampliar, y cuando basta se crea el ticket.</li>
 * </ol>
 * Si el usuario rechaza la incidencia o pide otra cosa en el gate, el router
 * descarta el borrador y reencamina el turno (y este nodo trae además una red de
 * seguridad para un "no" explícito en la confirmación).
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
    private final Verifier verifier;

    @Override
    public Map<String, Object> apply(SupportState state) {
        Awaiting awaiting = state.awaiting();
        String message = state.userMessage().trim();
        log.debug("---SUPPORT--- awaiting={} confirmation={} draftLength={}",
                awaiting, state.confirmation(), state.ticketDraft().length());

        // PASO 1 — Propuesta: entrada fresca sin borrador previo. El Verifier confirma que el
        // mensaje es realmente una petición de incidencia antes de comprometerse a pedirla.
        // Se siembra el borrador SIEMPRE (incluso en DOUBT/REJECT) para que, si el usuario
        // confirma en el turno siguiente, PASO 1 no se repita (ticketDraft ya no está vacío).
        if (awaiting != Awaiting.TICKET_CONFIRM && awaiting != Awaiting.TICKET_INFO
                && state.ticketDraft().isBlank()) {
            String ticketDraft = message;
            String focus = state.orderId().map(id -> "#" + id).orElse("(ninguno)");
            Verifier.Decision decision = verifier.verify(message, Intent.SUPPORT.name(), focus);
            Verdict verdict = Verdict.fromLlm(decision.verdict());
            log.debug("---SUPPORT--- verify verdict={} reason={}", verdict, decision.reason());

            if (!SupportRouting.proceedsAfterVerify(verdict, Intent.SUPPORT)) {
                // DOUBT o REJECT: aclarar en vez de comprometerse. El borrador queda sembrado
                // para que el turno de confirmación caiga directamente en PASO 3.
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

            // SURE: confirmar y pedir el detalle en un solo paso (TICKET_INFO, no TICKET_CONFIRM)
            return Map.of(
                    "ticketDraft", ticketDraft,
                    "awaiting", Awaiting.TICKET_INFO.name(),
                    "toolResult", "El usuario quiere CONFIRMAR que se abra una incidencia. Confirma "
                            + "que vas a gestionarlo y pídele que describa con detalle el problema "
                            + "para poder registrar la incidencia correctamente.");
        }

        // PASO 2 — Confirmación: estábamos esperando el sí/no.
        if (awaiting == Awaiting.TICKET_CONFIRM) {
            if ("NO".equals(state.confirmation())) {
                // Rechazo explícito (red de seguridad: normalmente el router ya reencamina al ver
                // que la intención dejó de ser SUPPORT). No se abre nada.
                Map<String, Object> declined = new HashMap<>();
                declined.put("awaiting", Awaiting.NOTHING.name());
                declined.put("ticketDraft", AgentState.MARK_FOR_REMOVAL);
                declined.put("resolved", true);
                declined.put("toolResult", "El usuario NO quiere abrir la incidencia. Reconduce con "
                        + "naturalidad hacia en qué más puedes ayudarle, sin abrir nada.");
                return declined;
            }
            // Confirmación escueta (un "sí" sin detalle): ahora sí se le pide la descripción.
            boolean briefConfirmation = "YES".equals(state.confirmation())
                    && message.length() < MIN_DETAIL_LENGTH;
            if (briefConfirmation) {
                return Map.of(
                        "awaiting", Awaiting.TICKET_INFO.name(),
                        "toolResult", "El usuario confirma que quiere abrir la incidencia. Pídele que "
                                + "describa con detalle qué ha ocurrido para poder abrirla. No "
                                + "inventes datos.");
            }
            // El mensaje ya trae la descripción (confirmar y describir en uno): se trata como detalle.
            return collectOrCreate(state, message);
        }

        // PASO 3 — Descripción: el usuario describe el problema. Se acumula SOLO el detalle real
        // (sin ruido de la selección de pedido ni del "sí" de confirmación).
        String rawDescription = state.ticketDraft().isBlank()
                ? message
                : state.ticketDraft() + "\n" + message;
        return collectOrCreate(state, rawDescription);
    }

    /** Con la descripción acumulada: si aún es corta pide más; si basta, crea la incidencia. */
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

    /** Redacta y persiste la incidencia, emite la tarjeta-enlace y cierra la gestión. */
    private Map<String, Object> createTicket(SupportState state, String rawDescription) {
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
