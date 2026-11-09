package io.github.mikel0ne.l1copilot.service;

import dev.langchain4j.exception.LangChain4jException;
import io.github.mikel0ne.l1copilot.agent.Awaiting;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    private final CompiledGraph<SupportState> supportAgent;
    private final UserRepository userRepository;
    private final TreatmentPolicy treatmentPolicy;
    private final TurnLogger turnLogger;

    public ChatResult chat(String userId, String conversationId, String message) {
        log.debug("Conversation turn {} (user {})", conversationId, userId);

        Treatment treatment = userRepository.findById(userId)
                .map(treatmentPolicy::treatmentFor)
                .orElse(Treatment.TU);

        RunnableConfig config = RunnableConfig.builder()
                .threadId(conversationId)
                .build();

        Map<String, Object> input = new HashMap<>();
        input.put("userId", userId);
        input.put("treatment", treatment.name());
        input.put("userMessage", message);
        input.put("history", List.of("USER: " + message));
        input.put("confirmation", "NONE");
        input.put("tentative", false);

        input.put("navigating", false);

        input.put("proposedOrderId", 0L);
        input.put("toolResult", "");
        input.put("reply", "");
        input.put("resolved", false);
        input.put("orderCards", List.of());
        input.put("ticketCards", List.of());
        input.put("returnCards", List.of());

        input.put("pendingCancelId", 0L);

        input.put("pendingReturnId", 0L);

        SupportState state;
        try {
            state = supportAgent.invoke(input, config)
                    .orElseThrow(() -> new ConversationException("The graph returned no final state"));
        } catch (ConversationException e) {
            throw e;
        } catch (RuntimeException e) {
            if (causedByLlm(e)) {
                log.error("Model failure in conversation {}", conversationId, e);
                throw new LlmUnavailableException(e);
            }
            log.error("Failure processing a turn of conversation {}", conversationId, e);
            throw new ConversationException("Error processing the message", e);
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

        ConfirmActionDto confirm = state.pendingCancelId()
                .map(id -> cancelConfirmation(id, state.treatment()))
                .or(() -> state.pendingReturnId().map(id -> returnConfirmation(id, state.treatment())))
                .orElse(null);

        String reply = stripThoughts(state.reply());

        boolean ordersSelectable = state.awaiting() == Awaiting.ORDER_CHOICE;

        turnLogger.record(new TurnLogger.TurnLog(
                conversationId, userId, message,
                state.intent().name(), state.awaiting().name(), state.orderId().orElse(null),
                orders.size(), tickets.size(), state.resolved(), reply));

        return new ChatResult(reply, state.intent().name(),
                state.resolved(), orders, ordersSelectable, tickets, returns, confirm);
    }

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

    private static String stripThoughts(String reply) {
        if (reply == null) return "";
        return reply.replaceAll("(?si)\\s*<thought>.*?</thought>\\s*", "").trim();
    }

    private boolean causedByLlm(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof LangChain4jException) {
                return true;
            }
        }
        return false;
    }

    public record ChatResult(String reply, String intent, boolean resolved,
                             List<OrderCardDto> orders, boolean ordersSelectable,
                             List<TicketCardDto> tickets,
                             List<ReturnCardDto> returns, ConfirmActionDto confirm) {
    }

    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(Throwable cause) {
            super("El modelo de lenguaje no está disponible", cause);
        }
    }

    public static class ConversationException extends RuntimeException {
        public ConversationException(String message) {
            super(message);
        }

        public ConversationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
