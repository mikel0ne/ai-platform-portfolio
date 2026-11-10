package io.github.mikel0ne.l1copilot.agent;

import io.github.mikel0ne.l1copilot.dto.OrderCardDto;
import io.github.mikel0ne.l1copilot.dto.ReturnCardDto;
import io.github.mikel0ne.l1copilot.dto.TicketCardDto;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SupportState extends AgentState {

    public static final String USER_ID = "userId";
    public static final String USER_MESSAGE = "userMessage";
    public static final String TREATMENT = "treatment";
    public static final String HISTORY = "history";
    public static final String INTENT = "intent";
    public static final String SUSPENDED_INTENT = "suspendedIntent";
    public static final String ORDER_ID = "orderId";
    public static final String CANDIDATES = "candidates";
    public static final String PROPOSED_ORDER_ID = "proposedOrderId";
    public static final String AWAITING = "awaiting";
    public static final String CANCELLABLE = "cancellable";
    public static final String RETURNABLE = "returnable";
    public static final String ORDER_RELATED = "orderRelated";
    public static final String VERDICT = "verdict";
    public static final String TENTATIVE = "tentative";
    public static final String NAVIGATING = "navigating";
    public static final String CLARIFY_ATTEMPTS = "clarifyAttempts";
    public static final String PENDING_CANCEL_ID = "pendingCancelId";
    public static final String PENDING_RETURN_ID = "pendingReturnId";
    public static final String CONFIRMATION = "confirmation";
    public static final String TICKET_DRAFT = "ticketDraft";
    public static final String TOOL_RESULT = "toolResult";
    public static final String REPLY = "reply";
    public static final String RESOLVED = "resolved";
    public static final String ORDER_CARDS = "orderCards";
    public static final String TICKET_CARDS = "ticketCards";
    public static final String RETURN_CARDS = "returnCards";

    // Only simple serializable values: the checkpointer persists the state between HTTP turns.
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            HISTORY, Channels.appender(ArrayList::new)
    );

    public SupportState(Map<String, Object> initData) {
        super(initData);
    }

    public String userId() {
        return this.<String>value(USER_ID)
                .orElseThrow(() -> new IllegalStateException("userId missing from the graph state"));
    }

    public String userMessage() {
        return this.value(USER_MESSAGE, "");
    }

    public Treatment treatment() {
        return Treatment.fromState(this.value(TREATMENT, Treatment.TU.name()));
    }

    public List<String> history() {
        return this.value(HISTORY, List::of);
    }

    public Intent intent() {
        return Intent.fromLlm(this.value(INTENT, Intent.NONE.name()));
    }

    public Optional<Intent> suspendedIntent() {
        return this.<String>value(SUSPENDED_INTENT).map(Intent::fromLlm);
    }

    public Optional<Long> orderId() {
        return this.value(ORDER_ID);
    }

    public List<String> candidates() {
        return this.value(CANDIDATES, List::of);
    }

    public Optional<Long> proposedOrderId() {
        return this.<Number>value(PROPOSED_ORDER_ID)
                .map(Number::longValue)
                .filter(id -> id > 0);
    }

    public Awaiting awaiting() {
        return Awaiting.fromState(this.value(AWAITING, Awaiting.NOTHING.name()));
    }

    public boolean cancellable() {
        return this.value(CANCELLABLE, false);
    }

    public boolean returnable() {
        return this.value(RETURNABLE, false);
    }

    public boolean orderRelated() {
        return this.value(ORDER_RELATED, false);
    }

    public Verdict verdict() {
        return Verdict.fromLlm(this.value(VERDICT, Verdict.SURE.name()));
    }

    public boolean tentative() {
        return this.value(TENTATIVE, false);
    }

    public boolean navigating() {
        return this.value(NAVIGATING, false);
    }

    public int clarifyAttempts() {
        return this.<Number>value(CLARIFY_ATTEMPTS).map(Number::intValue).orElse(0);
    }

    public Optional<Long> pendingCancelId() {
        return this.<Number>value(PENDING_CANCEL_ID)
                .map(Number::longValue)
                .filter(id -> id > 0);
    }

    public Optional<Long> pendingReturnId() {
        return this.<Number>value(PENDING_RETURN_ID)
                .map(Number::longValue)
                .filter(id -> id > 0);
    }

    public Confirmation confirmation() {
        return Confirmation.fromState(this.value(CONFIRMATION, Confirmation.NONE.name()));
    }

    public String ticketDraft() {
        return this.value(TICKET_DRAFT, "");
    }

    public String toolResult() {
        return this.value(TOOL_RESULT, "");
    }

    public String reply() {
        return this.value(REPLY, "");
    }

    public boolean resolved() {
        return this.value(RESOLVED, false);
    }

    public List<OrderCardDto> orderCards() {
        return this.value(ORDER_CARDS, List::of);
    }

    public List<TicketCardDto> ticketCards() {
        return this.value(TICKET_CARDS, List::of);
    }

    public List<ReturnCardDto> returnCards() {
        return this.value(RETURN_CARDS, List::of);
    }
}
