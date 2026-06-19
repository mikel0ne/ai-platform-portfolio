package io.github.mikel0ne.l1copilot.service;

import dev.langchain4j.exception.LangChain4jException;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.Treatment;
import io.github.mikel0ne.l1copilot.agent.UiAction;
import io.github.mikel0ne.l1copilot.config.TreatmentPolicy;
import io.github.mikel0ne.l1copilot.domain.repository.UserRepository;
import io.github.mikel0ne.l1copilot.dto.ConfirmActionDto;
import io.github.mikel0ne.l1copilot.dto.OrderCardDto;
import io.github.mikel0ne.l1copilot.dto.ReturnCardDto;
import io.github.mikel0ne.l1copilot.dto.TicketCardDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Puente entre la API REST y el grafo: un turno HTTP = una invocación del
 * grafo con {@code threadId} = id de conversación (ADR-2). El checkpointer
 * restaura el estado entre turnos, lo que permite los ciclos de clarificación.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    private final CompiledGraph<SupportState> supportAgent;
    private final UserRepository userRepository;
    private final TreatmentPolicy treatmentPolicy;
    private final TurnLogger turnLogger;

    public ChatResult chat(String userId, String conversationId, String message) {
        log.debug("Turno de conversación {} (usuario {})", conversationId, userId);

        Treatment treatment = userRepository.findById(userId)
                .map(treatmentPolicy::treatmentFor)
                .orElse(Treatment.TU);

        RunnableConfig config = RunnableConfig.builder()
                .threadId(conversationId)
                .build();

        // Valores por turno: el resto del estado (intent, orderId, awaiting,
        // candidates, ticketDraft) lo restaura el checkpointer.
        Map<String, Object> input = new HashMap<>();
        input.put("userId", userId);
        input.put("treatment", treatment.name());
        input.put("userMessage", message);
        input.put("history", List.of("USER: " + message));
        input.put("confirmation", "NONE");
        input.put("tentative", false);
        // Efímero: solo lo activa el router al detectar una navegación (volver al listado/menú).
        input.put("navigating", false);
        // Efímero: foco propuesto por el router a la espera de que verify lo confirme.
        input.put("proposedOrderId", 0L);
        input.put("toolResult", "");
        input.put("reply", "");
        input.put("resolved", false);
        input.put("orderCards", List.of());
        input.put("ticketCards", List.of());
        input.put("returnCards", List.of());
        // Efímero: solo lo (re)activa cancel_check en el turno que pide confirmar.
        input.put("pendingCancelId", 0L);
        // Efímero: solo lo (re)activa return_check en el turno que pide confirmar.
        input.put("pendingReturnId", 0L);

        SupportState state;
        try {
            state = supportAgent.invoke(input, config)
                    .orElseThrow(() -> new ConversationException("El grafo no devolvió estado final"));
        } catch (ConversationException e) {
            throw e;
        } catch (RuntimeException e) {
            if (causedByLlm(e)) {
                log.error("Fallo del modelo en la conversación {}", conversationId, e);
                throw new LlmUnavailableException(e);
            }
            log.error("Fallo procesando el turno de la conversación {}", conversationId, e);
            throw new ConversationException("Error procesando el mensaje", e);
        }

        log.debug("Turno completado: intent={} resolved={}", state.intent(), state.resolved());

        List<OrderCardDto> orders = state.orderCards().stream()
                .map(m -> new OrderCardDto(
                        m.get("id") instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(m.get("id"))),
                        String.valueOf(m.get("description")),
                        String.valueOf(m.get("status")),
                        m.get("imageUrl") != null ? String.valueOf(m.get("imageUrl")) : null))
                .toList();

        List<TicketCardDto> tickets = state.ticketCards().stream()
                .map(m -> new TicketCardDto(
                        m.get("id") instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(m.get("id"))),
                        String.valueOf(m.get("code")),
                        String.valueOf(m.get("title")),
                        String.valueOf(m.get("status")),
                        String.valueOf(m.get("createdAt"))))
                .toList();

        List<ReturnCardDto> returns = state.returnCards().stream()
                .map(m -> new ReturnCardDto(
                        m.get("id") instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(m.get("id"))),
                        String.valueOf(m.get("code")),
                        String.valueOf(m.get("description")),
                        String.valueOf(m.get("status")),
                        String.valueOf(m.get("createdAt"))))
                .toList();

        // Una operación irreversible distinta puede estar a la espera de confirmación:
        // la cancelación o la devolución; nunca ambas en el mismo turno.
        ConfirmActionDto confirm = state.pendingCancelId()
                .map(id -> cancelConfirmation(id, state.treatment()))
                .or(() -> state.pendingReturnId().map(id -> returnConfirmation(id, state.treatment())))
                .orElse(null);

        String reply = stripThoughts(state.reply());

        // Materia prima del golden-set: una línea por turno en un fichero JSONL
        // (fuera de la BD, sobrevive a reinicios de H2 y al reset del seed).
        turnLogger.record(new TurnLogger.TurnLog(
                conversationId, userId, message,
                state.intent().name(), state.awaiting().name(), state.orderId().orElse(null),
                orders.size(), tickets.size(), state.resolved(), reply));

        return new ChatResult(reply, state.intent().name(),
                state.resolved(), orders, tickets, returns, confirm);
    }

    /** Acción de confirmación (botones) para cancelar el pedido, con el tratamiento adecuado. */
    private static ConfirmActionDto cancelConfirmation(long orderId, Treatment treatment) {
        String title = treatment == Treatment.USTED
                ? String.format("¿Confirma la cancelación del pedido #%d?", orderId)
                : String.format("¿Confirmas la cancelación del pedido #%d?", orderId);
        return new ConfirmActionDto(
                orderId,
                title,
                "Esta acción no se puede deshacer.",
                "Sí, cancelar el pedido", UiAction.confirmCancel(orderId),
                "No, conservarlo", UiAction.abortCancel(orderId));
    }

    /** Acción de confirmación (botones) para iniciar la devolución del pedido. */
    private static ConfirmActionDto returnConfirmation(long orderId, Treatment treatment) {
        String title = treatment == Treatment.USTED
                ? String.format("¿Confirma que desea iniciar la devolución del pedido #%d?", orderId)
                : String.format("¿Confirmas que quieres iniciar la devolución del pedido #%d?", orderId);
        return new ConfirmActionDto(
                orderId,
                title,
                "Se generará una etiqueta de devolución para este pedido.",
                "Sí, iniciar la devolución", UiAction.confirmReturn(orderId),
                "No, conservarlo", UiAction.abortReturn(orderId));
    }

    /**
     * Elimina bloques {@code <thought>…</thought>} que algunos modelos incluyen
     * en su respuesta antes del texto visible al usuario. El filtrado se aplica
     * aquí, en el backend, para que ningún cliente reciba razonamientos internos
     * independientemente del canal (REST, tests, futuros clientes).
     */
    private static String stripThoughts(String reply) {
        if (reply == null) return "";
        return reply.replaceAll("(?si)\\s*<thought>.*?</thought>\\s*", "").trim();
    }

    /** El grafo envuelve las excepciones de los nodos: se inspecciona la cadena de causas. */
    private boolean causedByLlm(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof LangChain4jException) {
                return true;
            }
        }
        return false;
    }

    /** Resultado de un turno, listo para mapear al DTO de la API. */
    public record ChatResult(String reply, String intent, boolean resolved,
                             List<OrderCardDto> orders, List<TicketCardDto> tickets,
                             List<ReturnCardDto> returns, ConfirmActionDto confirm) {
    }

    /** El modelo (Gemini) no está disponible o agotó la cuota. */
    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(Throwable cause) {
            super("El modelo de lenguaje no está disponible", cause);
        }
    }

    /** Error interno procesando el turno. */
    public static class ConversationException extends RuntimeException {
        public ConversationException(String message) {
            super(message);
        }

        public ConversationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
