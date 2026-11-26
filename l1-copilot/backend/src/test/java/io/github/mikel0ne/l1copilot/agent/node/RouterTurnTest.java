package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier.RouterDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RouterTurnTest {

    private RouterTurn turn(String intent, Map<String, Object> data) {
        return new RouterTurn(new SupportState(new HashMap<>(data)),
                new RouterDecision(intent, null, "NONE", "NONE"));
    }

    @Test
    @DisplayName("The effective awaiting is the state's until the turn writes another one")
    void effectiveAwaiting() {
        RouterTurn turn = turn("TRACKING", Map.of(SupportState.AWAITING, "ORDER_CHOICE"));

        assertThat(turn.effectiveAwaiting()).isEqualTo(Awaiting.ORDER_CHOICE);
        turn.setAwaiting(Awaiting.NOTHING);
        assertThat(turn.effectiveAwaiting()).isEqualTo(Awaiting.NOTHING);
        assertThat(turn.state().awaiting()).isEqualTo(Awaiting.ORDER_CHOICE);
    }

    @Test
    @DisplayName("The ticket draft counts as blank once the turn drops it")
    void effectiveTicketDraftBlank() {
        RouterTurn turn = turn("TRACKING", Map.of(SupportState.TICKET_DRAFT, "draft"));

        assertThat(turn.effectiveTicketDraftBlank()).isFalse();
        turn.dropTicketDraft();
        assertThat(turn.effectiveTicketDraftBlank()).isTrue();
    }

    @Test
    @DisplayName("The effective order is the state's until the turn sets a focus")
    void effectiveOrderId() {
        RouterTurn turn = turn("TRACKING", Map.of(SupportState.ORDER_ID, 1L));

        assertThat(turn.effectiveOrderId()).isEqualTo(Optional.of(1L));
        turn.setFocus(2);
        assertThat(turn.effectiveOrderId()).isEqualTo(Optional.of(2L));
        assertThat(turn.state().orderId()).isEqualTo(Optional.of(1L));
    }

    @Test
    @DisplayName("A proposed focus is only visible after the turn proposes it")
    void focusProposed() {
        RouterTurn turn = turn("TRACKING", Map.of());

        assertThat(turn.focusProposed()).isFalse();
        turn.proposeFocus(5);
        assertThat(turn.focusProposed()).isTrue();
    }

    @Test
    @DisplayName("Abandoning the ticket gate: awaiting ticket info with an intent other than SUPPORT or NONE")
    void abandonsTicketGate() {
        Map<String, Object> awaitingTicket = Map.of(SupportState.AWAITING, "TICKET_INFO");

        assertThat(turn("TRACKING", awaitingTicket).abandonsTicketGate()).isTrue();
        assertThat(turn("SUPPORT", awaitingTicket).abandonsTicketGate()).isFalse();
        assertThat(turn(null, awaitingTicket).abandonsTicketGate()).isFalse();
        assertThat(turn("TRACKING", Map.of(SupportState.AWAITING, "NOTHING")).abandonsTicketGate()).isFalse();
    }

    @Test
    @DisplayName("A stale ticket draft: nothing awaited, a draft kept and an intent other than SUPPORT or NONE")
    void leavesStaleTicketDraft() {
        Map<String, Object> withDraft = Map.of(SupportState.AWAITING, "NOTHING", SupportState.TICKET_DRAFT, "draft");

        assertThat(turn("FAQ", withDraft).leavesStaleTicketDraft()).isTrue();
        assertThat(turn("SUPPORT", withDraft).leavesStaleTicketDraft()).isFalse();
        assertThat(turn("FAQ", Map.of(SupportState.AWAITING, "NOTHING")).leavesStaleTicketDraft()).isFalse();
    }

    @Test
    @DisplayName("Typed writes keep the stored types: Long ids, Integer counters and enum names")
    void typedWritesKeepStoredTypes() {
        RouterTurn turn = turn("TRACKING", Map.of());

        turn.setFocus(3);
        turn.proposeFocus(4);
        turn.setClarifyAttempts(2);
        turn.setAwaiting(Awaiting.CANCEL_CONFIRM);

        assertThat(turn.updates())
                .containsEntry(SupportState.ORDER_ID, 3L)
                .containsEntry(SupportState.PROPOSED_ORDER_ID, 4L)
                .containsEntry(SupportState.CLARIFY_ATTEMPTS, 2)
                .containsEntry(SupportState.AWAITING, "CANCEL_CONFIRM");
    }
}
