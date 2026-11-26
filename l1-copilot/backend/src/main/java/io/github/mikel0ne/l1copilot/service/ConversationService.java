package io.github.mikel0ne.l1copilot.service;

import dev.langchain4j.exception.LangChain4jException;
import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.Treatment;
import io.github.mikel0ne.l1copilot.agent.Turn;
import io.github.mikel0ne.l1copilot.agent.UiAction;
import io.github.mikel0ne.l1copilot.config.TreatmentPolicy;
import io.github.mikel0ne.l1copilot.domain.repository.UserRepository;
import io.github.mikel0ne.l1copilot.dto.ConfirmActionDto;
import io.github.mikel0ne.l1copilot.dto.OrderCardDto;
import io.github.mikel0ne.l1copilot.dto.ReturnCardDto;
import io.github.mikel0ne.l1copilot.dto.TicketCardDto;
import io.github.mikel0ne.l1copilot.observability.LangfuseTurnTracer;
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
    private final LangfuseTurnTracer turnTracer;

    public ChatResult chat(String userId, String conversationId, String message) {
        try (LangfuseTurnTracer.TurnTrace trace = turnTracer.start(userId, conversationId, message)) {
            return runTurn(trace, userId, conversationId, message);
        }
    }

    private ChatResult runTurn(LangfuseTurnTracer.TurnTrace trace,
                               String userId, String conversationId, String message) {
        log.debug("Conversation turn {} (user {})", conversationId, userId);

        Treatment treatment = userRepository.findById(userId)
                .map(treatmentPolicy::treatmentFor)
                .orElse(Treatment.TU);

        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId(userId, conversationId))
                .build();

        Map<String, Object> input = new HashMap<>(SupportState.TURN_RESETS);
        input.put(SupportState.USER_ID, userId);
        input.put(SupportState.TREATMENT, treatment.name());
        input.put(SupportState.USER_MESSAGE, message);
        input.put(SupportState.HISTORY, List.of(Turn.user(message)));

        SupportState state;
        try {
            state = supportAgent.invoke(input, config)
                    .orElseThrow(() -> new ConversationException("The graph returned no final state"));
        } catch (ConversationException e) {
            trace.fail(e);
            throw e;
        } catch (RuntimeException e) {
            trace.fail(e);
            if (causedByLlm(e)) {
                log.error("Model failure in conversation {}", conversationId, e);
                throw new LlmUnavailableException(e);
            }
            log.error("Failure processing a turn of conversation {}", conversationId, e);
            throw new ConversationException("Error processing the message", e);
        }

        log.debug("Turn completed: intent={} resolved={}", state.intent(), state.resolved());

        List<OrderCardDto> orders = state.orderCards();
        List<TicketCardDto> tickets = state.ticketCards();
        List<ReturnCardDto> returns = state.returnCards();

        ConfirmActionDto confirm = state.pendingCancelId()
                .map(id -> cancelConfirmation(id, state.treatment()))
                .or(() -> state.pendingReturnId().map(id -> returnConfirmation(id, state.treatment())))
                .orElse(null);

        String reply = stripThoughts(state.reply());

        boolean ordersSelectable = state.awaiting() == Awaiting.ORDER_CHOICE;

        trace.complete(state, reply, orders.size(), tickets.size());

        turnLogger.record(new TurnLogger.TurnLog(
                conversationId, userId, message,
                state.intent().name(), state.awaiting().name(), state.orderId().orElse(null),
                orders.size(), tickets.size(), state.resolved(), reply, trace.traceId()));

        return new ChatResult(reply, state.intent().name(),
                state.resolved(), orders, ordersSelectable, tickets, returns, confirm);
    }

    // The conversation id comes from the client, so the checkpoint is keyed by user too.
    static String threadId(String userId, String conversationId) {
        return userId + ":" + conversationId;
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
            super("The language model is unavailable", cause);
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
