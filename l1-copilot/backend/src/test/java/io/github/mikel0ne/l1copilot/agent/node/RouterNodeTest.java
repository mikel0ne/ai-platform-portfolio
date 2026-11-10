package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.UiAction;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier.RouterDecision;
import io.github.mikel0ne.l1copilot.config.ConversationHistoryProperties;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RouterNodeTest {

    private final IntentClassifier classifier = mock(IntentClassifier.class);
    private final RouterNode router = new RouterNode(classifier, new ConversationHistoryProperties(null, null));

    private SupportState state(Map<String, Object> data) {
        return new SupportState(new HashMap<>(data));
    }

    private void decision(RouterDecision d) {
        when(classifier.classify(any(), any(), any(), any(), any(), any())).thenReturn(d);
    }

    @Test
    @DisplayName("Sticky focus: with an order in progress and orderId=null, the order does NOT change")
    void nullOrderKeepsFocus() {
        decision(new RouterDecision("SUPPORT", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of("orderId", 789L, "awaiting", "NOTHING")));

        assertThat(updates).doesNotContainKey("orderId");
        assertThat(updates).containsEntry("intent", "SUPPORT");
    }

    @Test
    @DisplayName("Explicit switch: naming ANOTHER order (fresh request) PROPOSES the focus (verify confirms it)")
    void explicitDifferentOrderSwitchesFocus() {
        decision(new RouterDecision("TRACKING", 456L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of("orderId", 789L, "awaiting", "NOTHING")));

        assertThat(updates).containsEntry("proposedOrderId", 456L);
        assertThat(updates).doesNotContainKey("orderId");
        assertThat(updates).doesNotContainKey("candidates");
    }

    @Test
    @DisplayName("Menu choice: in ORDER_CHOICE the chosen order is proposed and the question is closed (verify confirms it)")
    void menuChoiceResolvesOrder() {
        decision(new RouterDecision("TRACKING", 789L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "awaiting", "ORDER_CHOICE",
                "candidates", List.of("#123 Portátil (IN_DELIVERY)", "#789 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry("proposedOrderId", 789L);
        assertThat(updates).doesNotContainKey("orderId");
        assertThat(updates).containsEntry("awaiting", "NOTHING");
    }

    @Test
    @DisplayName("Back to list: BACK_TO_LIST releases the focus, drops the menu and reroutes to the list")
    void backToListReleasesFocus() {
        decision(new RouterDecision("OTHER", null, "NONE", "BACK_TO_LIST"));

        Map<String, Object> updates = router.apply(state(Map.of("orderId", 789L, "intent", "TRACKING")));

        assertThat(updates).containsEntry("orderId", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("candidates", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("awaiting", "NOTHING");

        assertThat(updates).containsEntry("intent", "TRACKING");
    }

    @Test
    @DisplayName("Back to list during a cancellation keeps the CANCEL intent")
    void backToListKeepsCancelIntent() {
        decision(new RouterDecision("OTHER", null, "NONE", "BACK_TO_LIST"));

        Map<String, Object> updates = router.apply(state(Map.of("orderId", 789L, "intent", "CANCEL")));

        assertThat(updates).containsEntry("orderId", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("intent", "CANCEL");
    }

    @Test
    @DisplayName("Back to main menu: BACK_TO_MENU releases the focus and always falls to OTHER (fallback)")
    void backToMenuReleasesFocusAndFallsBackToOther() {
        decision(new RouterDecision("TRACKING", null, "NONE", "BACK_TO_MENU"));

        Map<String, Object> updates = router.apply(state(Map.of("orderId", 789L, "intent", "TRACKING")));

        assertThat(updates).containsEntry("orderId", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("candidates", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("ticketDraft", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("awaiting", "NOTHING");

        assertThat(updates).containsEntry("intent", "OTHER");
    }

    @Test
    @DisplayName("'back' (BACK) with an order in focus: goes up one level to the list")
    void backWithFocusGoesToList() {
        decision(new RouterDecision("TRACKING", null, "NONE", "BACK"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "userMessage", "atrás",
                "orderId", 789L,
                "intent", "TRACKING")));

        assertThat(updates).containsEntry("orderId", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("awaiting", "NOTHING");

        assertThat(updates).containsEntry("intent", "TRACKING");
    }

    @Test
    @DisplayName("'back' (BACK) when already on the list (no focus): goes up to the menu, does NOT re-list")
    void backOnListWithoutFocusGoesToMenu() {
        decision(new RouterDecision("OTHER", null, "NONE", "BACK"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "userMessage", "atrás",
                "awaiting", "ORDER_CHOICE",
                "intent", "TRACKING",
                "candidates", List.of("#123 Portátil (IN_DELIVERY)", "#789 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry("orderId", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("candidates", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("awaiting", "NOTHING");

        assertThat(updates).containsEntry("intent", "OTHER");
    }

    @Test
    @DisplayName("'back' (BACK) while starting a return with an uncommitted deferred focus: returns to the list, NOT the menu")
    void backWithDeferredFocusDroppedGoesToList() {
        decision(new RouterDecision("OTHER", null, "NONE", "BACK"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "userMessage", "atrás",
                "awaiting", "NOTHING",
                "intent", "RETURN",
                "candidates", List.of("#123 Portátil (DELIVERED)", "#789 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry("orderId", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("candidates", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("awaiting", "NOTHING");

        assertThat(updates).containsEntry("intent", "RETURN");
    }

    @Test
    @DisplayName("Digression: an FAQ with a pending request suspends the task WITHOUT touching its context")
    void faqDuringPendingTaskSuspendsWithoutTouchingContext() {
        decision(new RouterDecision("FAQ", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "intent", "TRACKING",
                "awaiting", "ORDER_CHOICE",
                "candidates", List.of("#123 Portátil (IN_DELIVERY)", "#456 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry("intent", "FAQ");

        assertThat(updates).containsEntry("suspendedIntent", "TRACKING");
        assertThat(updates).doesNotContainKey("orderId");
        assertThat(updates).doesNotContainKey("candidates");
        assertThat(updates).doesNotContainKey("awaiting");
    }

    @Test
    @DisplayName("Digression: an FAQ with no pending request suspends nothing (plain FAQ)")
    void faqWithoutPendingTaskIsNotDigression() {
        decision(new RouterDecision("FAQ", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of("awaiting", "NOTHING")));

        assertThat(updates).containsEntry("intent", "FAQ");
        assertThat(updates).doesNotContainKey("suspendedIntent");
    }

    @Test
    @DisplayName("Resume: picking the request up after the FAQ restores the intent and clears the suspension")
    void resumingAfterDigressionClearsSuspendedIntent() {
        decision(new RouterDecision("TRACKING", 123L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "intent", "FAQ",
                "suspendedIntent", "TRACKING",
                "awaiting", "ORDER_CHOICE",
                "candidates", List.of("#123 Portátil (IN_DELIVERY)", "#456 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry("suspendedIntent", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("intent", "TRACKING");

        assertThat(updates).containsEntry("proposedOrderId", 123L);
        assertThat(updates).containsEntry("awaiting", "NOTHING");
    }

    @Test
    @DisplayName("Confirm button: the command resolves to YES deterministically, without the LLM")
    void confirmCancelCommandIsDeterministic() {
        Map<String, Object> updates = router.apply(state(Map.of(
                "userMessage", UiAction.confirmCancel(200L),
                "awaiting", "CANCEL_CONFIRM",
                "orderId", 200L)));

        assertThat(updates).containsEntry("intent", "CANCEL");
        assertThat(updates).containsEntry("orderId", 200L);
        assertThat(updates).containsEntry("confirmation", "YES");
        verifyNoInteractions(classifier);
    }

    @Test
    @DisplayName("Confirm button without a pending confirmation: it does not count as YES")
    void confirmCommandWithoutPendingConfirmationIsIgnored() {
        Map<String, Object> updates = router.apply(state(Map.of(
                "userMessage", UiAction.confirmCancel(200L))));

        assertThat(updates).containsEntry("intent", "CANCEL");
        assertThat(updates).containsEntry("confirmation", "NONE");
        verifyNoInteractions(classifier);
    }

    @Test
    @DisplayName("Confirm button for another order than the pending one: it does not count as YES")
    void confirmCommandForAnotherOrderIsIgnored() {
        Map<String, Object> updates = router.apply(state(Map.of(
                "userMessage", UiAction.confirmCancel(201L),
                "awaiting", "CANCEL_CONFIRM",
                "orderId", 200L)));

        assertThat(updates).containsEntry("orderId", 201L);
        assertThat(updates).containsEntry("confirmation", "NONE");
    }

    @Test
    @DisplayName("Return confirm button while a cancellation is pending: it does not count as YES")
    void returnConfirmDuringCancelConfirmationIsIgnored() {
        Map<String, Object> updates = router.apply(state(Map.of(
                "userMessage", UiAction.confirmReturn(200L),
                "awaiting", "CANCEL_CONFIRM",
                "orderId", 200L)));

        assertThat(updates).containsEntry("intent", "RETURN");
        assertThat(updates).containsEntry("confirmation", "NONE");
    }

    @Test
    @DisplayName("Dismiss button: the command resolves to NO deterministically, without the LLM")
    void abortCancelCommandIsDeterministic() {
        Map<String, Object> updates = router.apply(state(Map.of(
                "userMessage", UiAction.abortCancel(200L),
                "awaiting", "CANCEL_CONFIRM",
                "orderId", 200L)));

        assertThat(updates).containsEntry("confirmation", "NO");
        assertThat(updates).containsEntry("orderId", 200L);
        verifyNoInteractions(classifier);
    }

    @Test
    @DisplayName("Card selection '#id': sets the focus without the LLM when the id is on the menu")
    void cardSelectionResolvesFocusDeterministically() {
        Map<String, Object> updates = router.apply(state(Map.of(
                "userMessage", "#123",
                "awaiting", "ORDER_CHOICE",
                "candidates", List.of("#123 Portátil (IN_DELIVERY)", "#789 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry("orderId", 123L);
        assertThat(updates).containsEntry("awaiting", "NOTHING");
        verifyNoInteractions(classifier);
    }

    @Test
    @DisplayName("Chatty digression: small talk in the middle of a request suspends it like an FAQ")
    void chattyDuringPendingTaskSuspends() {
        decision(new RouterDecision("CHATTY", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "intent", "TRACKING",
                "awaiting", "ORDER_CHOICE",
                "candidates", List.of("#123 Portátil (IN_DELIVERY)"))));

        assertThat(updates).containsEntry("intent", "CHATTY");
        assertThat(updates).containsEntry("suspendedIntent", "TRACKING");
        assertThat(updates).doesNotContainKey("orderId");
        assertThat(updates).doesNotContainKey("awaiting");
    }

    @Test
    @DisplayName("Clarification: an ambiguous OTHER with focus increments the counter; without focus it resets it")
    void clarifyAttemptsTrackOnlyWithContext() {
        decision(new RouterDecision("OTHER", null, "NONE", "NONE"));

        Map<String, Object> withFocus = router.apply(state(Map.of(
                "intent", "TRACKING", "orderId", 789L, "clarifyAttempts", 1)));
        assertThat(withFocus).containsEntry("clarifyAttempts", 2);

        Map<String, Object> noFocus = router.apply(state(Map.of("clarifyAttempts", 3)));
        assertThat(noFocus).containsEntry("clarifyAttempts", 0);
    }

    @Test
    @DisplayName("SUPPORT: the orderRelated slot is copied to the state (it decides whether the order is identified)")
    void supportOrderRelatedIsDumped() {
        decision(new RouterDecision("SUPPORT", null, "NONE", "NONE", true));
        assertThat(router.apply(state(Map.of())))
                .containsEntry("intent", "SUPPORT")
                .containsEntry("orderRelated", true);

        decision(new RouterDecision("SUPPORT", null, "NONE", "NONE", false));
        assertThat(router.apply(state(Map.of())))
                .containsEntry("orderRelated", false);
    }

    @Test
    @DisplayName("orderRelated is only copied for SUPPORT; other intents leave it alone")
    void orderRelatedOnlyForSupport() {
        decision(new RouterDecision("TRACKING", 123L, "NONE", "NONE", true));
        assertThat(router.apply(state(Map.of()))).doesNotContainKey("orderRelated");
    }

    @Test
    @DisplayName("Ticket gate: if the user stays on SUPPORT, the draft is NOT dropped")
    void ticketGateKeepsDraftWhenStillSupport() {
        decision(new RouterDecision("SUPPORT", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "intent", "SUPPORT", "awaiting", "TICKET_CONFIRM", "ticketDraft", "el monitor llegó roto")));

        assertThat(updates).containsEntry("intent", "SUPPORT");

        assertThat(updates).doesNotContainKey("ticketDraft");
        assertThat(updates).doesNotContainEntry("awaiting", "NOTHING");
    }

    @Test
    @DisplayName("Ticket gate: if the user asks for something else, the draft is dropped and rerouted")
    void ticketGateAbandonedWhenUserWantsSomethingElse() {
        decision(new RouterDecision("OTHER", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "intent", "SUPPORT", "awaiting", "TICKET_CONFIRM", "ticketDraft", "tengo un problema")));

        assertThat(updates).containsEntry("ticketDraft", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("awaiting", "NOTHING");
        assertThat(updates).containsEntry("intent", "OTHER");

        assertThat(updates).containsEntry("clarifyAttempts", 0);
    }

    @Test
    @DisplayName("Ticket gate: pivoting to cancel another order drops the draft and PROPOSES the focus (verify confirms it)")
    void ticketGatePivotToCancelDropsDraftAndSwitchesOrder() {
        decision(new RouterDecision("CANCEL", 200L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "intent", "SUPPORT", "awaiting", "TICKET_CONFIRM", "ticketDraft", "esto va fatal")));

        assertThat(updates).containsEntry("ticketDraft", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("awaiting", "NOTHING");
        assertThat(updates).containsEntry("intent", "CANCEL");

        assertThat(updates).containsEntry("proposedOrderId", 200L);
        assertThat(updates).doesNotContainKey("orderId");
    }

    @Test
    @DisplayName("Ticket gate: a side FAQ SUSPENDS it (does not abandon it): the draft survives")
    void ticketGateFaqDigressionSuspendsWithoutDroppingDraft() {
        decision(new RouterDecision("FAQ", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "intent", "SUPPORT", "awaiting", "TICKET_CONFIRM", "ticketDraft", "el monitor llegó roto")));

        assertThat(updates).containsEntry("intent", "FAQ");
        assertThat(updates).containsEntry("suspendedIntent", "SUPPORT");

        assertThat(updates).doesNotContainKey("ticketDraft");
    }

    @Test
    @DisplayName("An '#id' not on the menu is no shortcut: the LLM resolves it (it is not set blindly)")
    void unknownCardIdFallsThroughToLlm() {
        decision(new RouterDecision("TRACKING", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "userMessage", "#999",
                "awaiting", "ORDER_CHOICE",
                "candidates", List.of("#123 Portátil (IN_DELIVERY)"))));

        assertThat(updates).doesNotContainEntry("orderId", 999L);
    }
}
