package io.github.mikel0ne.l1copilot.agent;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SupportState extends AgentState {

    // Only simple serializable values: the checkpointer persists the state between HTTP turns.
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            "history", Channels.appender(ArrayList::new)
    );

    public SupportState(Map<String, Object> initData) {
        super(initData);
    }

    public String userId() {
        return this.<String>value("userId")
                .orElseThrow(() -> new IllegalStateException("userId missing from the graph state"));
    }

    public String userMessage() {
        return this.value("userMessage", "");
    }

    public Treatment treatment() {
        return Treatment.fromState(this.value("treatment", Treatment.TU.name()));
    }

    public List<String> history() {
        return this.value("history", List::of);
    }

    public Intent intent() {
        return Intent.fromLlm(this.value("intent", Intent.NONE.name()));
    }

    public Optional<Intent> suspendedIntent() {
        return this.<String>value("suspendedIntent").map(Intent::fromLlm);
    }

    public Optional<Long> orderId() {
        return this.value("orderId");
    }

    public List<String> candidates() {
        return this.value("candidates", List::of);
    }

    public Optional<Long> proposedOrderId() {
        return this.<Number>value("proposedOrderId")
                .map(Number::longValue)
                .filter(id -> id > 0);
    }

    public Awaiting awaiting() {
        return Awaiting.fromState(this.value("awaiting", Awaiting.NOTHING.name()));
    }

    public boolean cancellable() {
        return this.value("cancellable", false);
    }

    public boolean returnable() {
        return this.value("returnable", false);
    }

    public boolean orderRelated() {
        return this.value("orderRelated", false);
    }

    public Verdict verdict() {
        return Verdict.fromLlm(this.value("verdict", Verdict.SURE.name()));
    }

    public boolean tentative() {
        return this.value("tentative", false);
    }

    public boolean navigating() {
        return this.value("navigating", false);
    }

    public int clarifyAttempts() {
        return this.<Number>value("clarifyAttempts").map(Number::intValue).orElse(0);
    }

    public Optional<Long> pendingCancelId() {
        return this.<Number>value("pendingCancelId")
                .map(Number::longValue)
                .filter(id -> id > 0);
    }

    public Optional<Long> pendingReturnId() {
        return this.<Number>value("pendingReturnId")
                .map(Number::longValue)
                .filter(id -> id > 0);
    }

    public String confirmation() {
        return this.value("confirmation", "NONE");
    }

    public String ticketDraft() {
        return this.value("ticketDraft", "");
    }

    public String toolResult() {
        return this.value("toolResult", "");
    }

    public String reply() {
        return this.value("reply", "");
    }

    public boolean resolved() {
        return this.value("resolved", false);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> orderCards() {
        return this.value("orderCards", List::of);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> ticketCards() {
        return this.value("ticketCards", List::of);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> returnCards() {
        return this.value("returnCards", List::of);
    }
}
