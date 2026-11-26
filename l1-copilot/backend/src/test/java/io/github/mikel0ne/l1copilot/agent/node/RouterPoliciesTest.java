package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier.RouterDecision;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RouterPoliciesTest {

    private RouterTurn turn(String intent, Long orderId, String confirmation, Map<String, Object> data) {
        return new RouterTurn(new SupportState(new HashMap<>(data)),
                new RouterDecision(intent, orderId, confirmation, "NONE"));
    }

    private RouterTurn turn(String intent, Map<String, Object> data) {
        return turn(intent, null, "NONE", data);
    }

    private RouterTurn focusTurn(String intent, long orderId, Map<String, Object> data) {
        return turn(intent, orderId, "NONE", data);
    }

    @Test
    @DisplayName("A digression while something is awaited suspends the current request")
    void digressionSuspendsTheRequest() {
        RouterTurn turn = turn("FAQ", Map.of(SupportState.AWAITING, "ORDER_CHOICE", SupportState.INTENT, "CANCEL"));

        assertThat(RouterPolicies.suspendForDigression(turn)).isTrue();
        assertThat(turn.updates())
                .containsOnlyKeys(SupportState.INTENT, SupportState.SUSPENDED_INTENT, SupportState.CLARIFY_ATTEMPTS)
                .containsEntry(SupportState.INTENT, "FAQ")
                .containsEntry(SupportState.SUSPENDED_INTENT, "CANCEL")
                .containsEntry(SupportState.CLARIFY_ATTEMPTS, 0);
    }

    @Test
    @DisplayName("A second digression keeps the request suspended by the first one")
    void digressionKeepsTheExistingSuspension() {
        RouterTurn turn = turn("CHATTY", Map.of(
                SupportState.AWAITING, "ORDER_CHOICE", SupportState.INTENT, "FAQ",
                SupportState.SUSPENDED_INTENT, "RETURN"));

        assertThat(RouterPolicies.suspendForDigression(turn)).isTrue();
        assertThat(turn.updates()).containsEntry(SupportState.SUSPENDED_INTENT, "RETURN");
    }

    @Test
    @DisplayName("A digression with nothing awaited suspends nothing")
    void digressionWithNothingAwaitedDoesNothing() {
        RouterTurn turn = turn("FAQ", Map.of(SupportState.AWAITING, "NOTHING"));

        assertThat(RouterPolicies.suspendForDigression(turn)).isFalse();
        assertThat(turn.updates()).isEmpty();
    }

    @Test
    @DisplayName("A non-digression turn resumes the suspended request")
    void resumeClearsTheSuspension() {
        RouterTurn turn = turn("TRACKING", Map.of(SupportState.SUSPENDED_INTENT, "CANCEL"));

        RouterPolicies.resumeAfterDigression(turn);

        assertThat(turn.updates()).containsEntry(SupportState.SUSPENDED_INTENT, AgentState.MARK_FOR_REMOVAL);
    }

    @Test
    @DisplayName("Another digression does not resume the suspended request")
    void resumeIgnoresDigressions() {
        RouterTurn turn = turn("CHATTY", Map.of(SupportState.SUSPENDED_INTENT, "CANCEL"));

        RouterPolicies.resumeAfterDigression(turn);

        assertThat(turn.updates()).isEmpty();
    }

    @Test
    @DisplayName("An undecided intent is not recorded")
    void noneIntentIsNotRecorded() {
        RouterTurn turn = turn(null, Map.of());

        RouterPolicies.recordIntent(turn);

        assertThat(turn.updates()).isEmpty();
    }

    @Test
    @DisplayName("SUPPORT records the intent and whether it concerns an order")
    void supportRecordsOrderRelated() {
        RouterTurn turn = new RouterTurn(new SupportState(new HashMap<>()),
                new RouterDecision("SUPPORT", null, "NONE", "NONE", true));

        RouterPolicies.recordIntent(turn);

        assertThat(turn.updates())
                .containsEntry(SupportState.INTENT, "SUPPORT")
                .containsEntry(SupportState.ORDER_RELATED, true);
    }

    @Test
    @DisplayName("Other intents do not record orderRelated")
    void trackingDoesNotRecordOrderRelated() {
        RouterTurn turn = turn("TRACKING", Map.of());

        RouterPolicies.recordIntent(turn);

        assertThat(turn.updates()).containsOnlyKeys(SupportState.INTENT);
    }

    @Test
    @DisplayName("Abandoning the ticket gate drops the draft and closes the question")
    void abandoningTheGateDropsTheDraft() {
        RouterTurn turn = turn("TRACKING", Map.of(SupportState.AWAITING, "TICKET_INFO", SupportState.TICKET_DRAFT, "d"));

        RouterPolicies.dropTicketDraft(turn);

        assertThat(turn.updates())
                .containsEntry(SupportState.TICKET_DRAFT, AgentState.MARK_FOR_REMOVAL)
                .containsEntry(SupportState.AWAITING, "NOTHING");
    }

    @Test
    @DisplayName("A stale draft is dropped without touching awaiting")
    void staleDraftIsDropped() {
        RouterTurn turn = turn("FAQ", Map.of(SupportState.AWAITING, "NOTHING", SupportState.TICKET_DRAFT, "d"));

        RouterPolicies.dropTicketDraft(turn);

        assertThat(turn.updates()).containsOnlyKeys(SupportState.TICKET_DRAFT);
    }

    @Test
    @DisplayName("Without an order id in the decision the focus does not move")
    void noOrderIdKeepsFocus() {
        RouterTurn nullId = turn("TRACKING", Map.of());
        RouterTurn zeroId = focusTurn("TRACKING", 0, Map.of());

        RouterPolicies.moveFocus(nullId);
        RouterPolicies.moveFocus(zeroId);

        assertThat(nullId.updates()).isEmpty();
        assertThat(zeroId.updates()).isEmpty();
    }

    @Test
    @DisplayName("An informational intent never moves the focus")
    void informationalIntentKeepsFocus() {
        RouterTurn turn = focusTurn("FAQ", 5, Map.of());

        RouterPolicies.moveFocus(turn);

        assertThat(turn.updates()).isEmpty();
    }

    @Test
    @DisplayName("A clean tracking turn proposes the focus for the verifier")
    void cleanTrackingProposesTheFocus() {
        RouterTurn turn = focusTurn("TRACKING", 5, Map.of(SupportState.AWAITING, "NOTHING"));

        RouterPolicies.moveFocus(turn);

        assertThat(turn.updates()).containsOnlyKeys(SupportState.PROPOSED_ORDER_ID)
                .containsEntry(SupportState.PROPOSED_ORDER_ID, 5L);
    }

    @Test
    @DisplayName("A SUPPORT turn naming a different order sets the focus and drops the candidates")
    void supportOnDifferentOrderSetsFocusAndDropsCandidates() {
        RouterTurn turn = focusTurn("SUPPORT", 5, Map.of(SupportState.ORDER_ID, 1L));

        RouterPolicies.moveFocus(turn);

        assertThat(turn.updates())
                .containsEntry(SupportState.ORDER_ID, 5L)
                .containsEntry(SupportState.CANDIDATES, AgentState.MARK_FOR_REMOVAL)
                .doesNotContainKey(SupportState.PROPOSED_ORDER_ID);
    }

    @Test
    @DisplayName("A SUPPORT turn naming the same order keeps the candidates")
    void supportOnSameOrderKeepsCandidates() {
        RouterTurn turn = focusTurn("SUPPORT", 5, Map.of(SupportState.ORDER_ID, 5L));

        RouterPolicies.moveFocus(turn);

        assertThat(turn.updates()).containsOnlyKeys(SupportState.ORDER_ID);
    }

    @Test
    @DisplayName("Choosing from the list closes the question and keeps the candidates")
    void choosingFromTheListKeepsCandidates() {
        RouterTurn turn = focusTurn("SUPPORT", 5, Map.of(SupportState.AWAITING, "ORDER_CHOICE"));

        RouterPolicies.moveFocus(turn);

        assertThat(turn.updates())
                .containsOnlyKeys(SupportState.AWAITING, SupportState.ORDER_ID)
                .containsEntry(SupportState.AWAITING, "NOTHING")
                .containsEntry(SupportState.ORDER_ID, 5L);
    }

    @Test
    @DisplayName("A cancel while a confirmation is pending sets the focus instead of proposing it")
    void actionDuringConfirmationSetsFocus() {
        RouterTurn turn = focusTurn("CANCEL", 5,
                Map.of(SupportState.AWAITING, "CANCEL_CONFIRM", SupportState.ORDER_ID, 1L));

        RouterPolicies.moveFocus(turn);

        assertThat(turn.updates()).containsEntry(SupportState.ORDER_ID, 5L)
                .doesNotContainKey(SupportState.PROPOSED_ORDER_ID);
    }

    @Test
    @DisplayName("Leaving the ticket gate for an order action proposes the focus")
    void leavingTheTicketGateProposesTheFocus() {
        RouterTurn turn = focusTurn("CANCEL", 5,
                Map.of(SupportState.AWAITING, "TICKET_INFO", SupportState.TICKET_DRAFT, "d"));

        RouterPolicies.dropTicketDraft(turn);
        RouterPolicies.moveFocus(turn);

        assertThat(turn.updates()).containsEntry(SupportState.PROPOSED_ORDER_ID, 5L)
                .doesNotContainKey(SupportState.ORDER_ID);
    }

    @Test
    @DisplayName("Without a pending confirmation no confirmation is bound")
    void noConfirmationWithoutPending() {
        RouterTurn turn = turn("CANCEL", null, "YES", Map.of(SupportState.AWAITING, "NOTHING"));

        RouterPolicies.bindConfirmation(turn);

        assertThat(turn.updates()).isEmpty();
    }

    @Test
    @DisplayName("A YES for the pending action and order is bound even when the router returns no intent")
    void yesForThePendingActionIsBound() {
        RouterTurn turn = turn(null, null, "YES", Map.of(
                SupportState.AWAITING, "CANCEL_CONFIRM", SupportState.INTENT, "CANCEL", SupportState.ORDER_ID, 7L));

        RouterPolicies.bindConfirmation(turn);

        assertThat(turn.updates()).containsEntry(SupportState.CONFIRMATION, "YES");
    }

    @Test
    @DisplayName("A YES that moves the focus to another order is ignored")
    void yesOnAnotherOrderIsIgnored() {
        RouterTurn turn = turn("CANCEL", 8L, "YES", Map.of(
                SupportState.AWAITING, "CANCEL_CONFIRM", SupportState.INTENT, "CANCEL", SupportState.ORDER_ID, 7L));

        RouterPolicies.moveFocus(turn);
        RouterPolicies.bindConfirmation(turn);

        assertThat(turn.updates()).containsEntry(SupportState.CONFIRMATION, "NONE");
    }

    @Test
    @DisplayName("A YES with the intent of another action is ignored")
    void yesWithAnotherIntentIsIgnored() {
        RouterTurn turn = turn("RETURN", null, "YES", Map.of(
                SupportState.AWAITING, "CANCEL_CONFIRM", SupportState.INTENT, "CANCEL", SupportState.ORDER_ID, 7L));

        RouterPolicies.bindConfirmation(turn);

        assertThat(turn.updates()).containsEntry(SupportState.CONFIRMATION, "NONE");
    }

    @Test
    @DisplayName("A NO is bound as it comes")
    void noIsBound() {
        RouterTurn turn = turn("CANCEL", null, "NO", Map.of(
                SupportState.AWAITING, "CANCEL_CONFIRM", SupportState.ORDER_ID, 7L));

        RouterPolicies.bindConfirmation(turn);

        assertThat(turn.updates()).containsEntry(SupportState.CONFIRMATION, "NO");
    }

    @Test
    @DisplayName("An unclear message with a focused order adds one clarify attempt")
    void otherWithFocusCountsAttempt() {
        RouterTurn turn = turn("OTHER", Map.of(SupportState.ORDER_ID, 7L, SupportState.CLARIFY_ATTEMPTS, 1));

        RouterPolicies.countClarifyAttempts(turn);

        assertThat(turn.updates()).containsEntry(SupportState.CLARIFY_ATTEMPTS, 2);
    }

    @Test
    @DisplayName("An unclear message with no ongoing context counts nothing")
    void otherWithoutContextResets() {
        RouterTurn turn = turn("OTHER", Map.of(SupportState.CLARIFY_ATTEMPTS, 1));

        RouterPolicies.countClarifyAttempts(turn);

        assertThat(turn.updates()).containsEntry(SupportState.CLARIFY_ATTEMPTS, 0);
    }

    @Test
    @DisplayName("An unclear message that abandons the ticket gate counts nothing")
    void otherAbandoningTheGateResets() {
        RouterTurn turn = turn("OTHER", Map.of(SupportState.AWAITING, "TICKET_INFO", SupportState.CLARIFY_ATTEMPTS, 1));

        RouterPolicies.countClarifyAttempts(turn);

        assertThat(turn.updates()).containsEntry(SupportState.CLARIFY_ATTEMPTS, 0);
    }

    @Test
    @DisplayName("A clear intent resets the clarify attempts")
    void clearIntentResets() {
        RouterTurn turn = turn("TRACKING", Map.of(SupportState.ORDER_ID, 7L, SupportState.CLARIFY_ATTEMPTS, 2));

        RouterPolicies.countClarifyAttempts(turn);

        assertThat(turn.updates()).containsEntry(SupportState.CLARIFY_ATTEMPTS, 0);
    }
}
