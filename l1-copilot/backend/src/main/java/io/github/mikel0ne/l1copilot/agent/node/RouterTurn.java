package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.Confirmation;
import io.github.mikel0ne.l1copilot.agent.Intent;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier.RouterDecision;
import org.bsc.langgraph4j.state.AgentState;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

// state() is the state at the start of the turn; only the effective* reads see what the policies wrote so far.
final class RouterTurn {

    private final SupportState state;
    private final RouterDecision decision;
    private final Intent intent;
    private final Map<String, Object> updates = new HashMap<>();

    RouterTurn(SupportState state, RouterDecision decision) {
        this.state = state;
        this.decision = decision;
        this.intent = Intent.fromLlm(decision.intent());
    }

    SupportState state() {
        return state;
    }

    RouterDecision decision() {
        return decision;
    }

    Intent intent() {
        return intent;
    }

    Map<String, Object> updates() {
        return updates;
    }

    boolean isDigression() {
        return intent == Intent.FAQ || intent == Intent.CHATTY;
    }

    // These intents never act on an order, so an orderId they carry must not become the focus the next turn inherits.
    boolean isInformational() {
        return intent == Intent.OTHER || intent == Intent.TICKETS || intent == Intent.FAQ
                || intent == Intent.CHATTY;
    }

    boolean abandonsTicketGate() {
        return state.awaiting() == Awaiting.TICKET_INFO && intent != Intent.SUPPORT && intent != Intent.NONE;
    }

    boolean leavesStaleTicketDraft() {
        return state.awaiting() == Awaiting.NOTHING && !state.ticketDraft().isBlank()
                && intent != Intent.SUPPORT && intent != Intent.NONE;
    }

    Awaiting effectiveAwaiting() {
        return updates.get(SupportState.AWAITING) instanceof String s ? Awaiting.fromState(s) : state.awaiting();
    }

    boolean effectiveTicketDraftBlank() {
        return updates.get(SupportState.TICKET_DRAFT) == AgentState.MARK_FOR_REMOVAL
                || state.ticketDraft().isBlank();
    }

    Optional<Long> effectiveOrderId() {
        return updates.get(SupportState.ORDER_ID) instanceof Long id ? Optional.of(id) : state.orderId();
    }

    boolean focusProposed() {
        return updates.get(SupportState.PROPOSED_ORDER_ID) != null;
    }

    void setIntent(Intent value) {
        updates.put(SupportState.INTENT, value.name());
    }

    void suspend(Intent value) {
        updates.put(SupportState.SUSPENDED_INTENT, value.name());
    }

    void clearSuspendedIntent() {
        updates.put(SupportState.SUSPENDED_INTENT, AgentState.MARK_FOR_REMOVAL);
    }

    void setOrderRelated(boolean value) {
        updates.put(SupportState.ORDER_RELATED, value);
    }

    void dropTicketDraft() {
        updates.put(SupportState.TICKET_DRAFT, AgentState.MARK_FOR_REMOVAL);
    }

    void setAwaiting(Awaiting value) {
        updates.put(SupportState.AWAITING, value.name());
    }

    void setFocus(long orderId) {
        updates.put(SupportState.ORDER_ID, orderId);
    }

    void proposeFocus(long orderId) {
        updates.put(SupportState.PROPOSED_ORDER_ID, orderId);
    }

    void dropCandidates() {
        updates.put(SupportState.CANDIDATES, AgentState.MARK_FOR_REMOVAL);
    }

    void setConfirmation(Confirmation value) {
        updates.put(SupportState.CONFIRMATION, value.name());
    }

    void setClarifyAttempts(int value) {
        updates.put(SupportState.CLARIFY_ATTEMPTS, value);
    }
}
