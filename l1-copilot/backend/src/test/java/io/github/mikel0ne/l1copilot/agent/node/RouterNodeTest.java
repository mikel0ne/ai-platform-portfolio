package io.github.mikel0ne.l1copilot.agent.node;

import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.ParameterizedTest;
import dev.langchain4j.service.output.OutputParsingException;
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

        Map<String, Object> updates = router.apply(state(Map.of(SupportState.ORDER_ID, 789L, SupportState.AWAITING, "NOTHING")));

        assertThat(updates).doesNotContainKey(SupportState.ORDER_ID);
        assertThat(updates).containsEntry(SupportState.INTENT, "SUPPORT");
    }

    @Test
    @DisplayName("Explicit switch: naming ANOTHER order (fresh request) PROPOSES the focus (verify confirms it)")
    void explicitDifferentOrderSwitchesFocus() {
        decision(new RouterDecision("TRACKING", 456L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(SupportState.ORDER_ID, 789L, SupportState.AWAITING, "NOTHING")));

        assertThat(updates).containsEntry(SupportState.PROPOSED_ORDER_ID, 456L);
        assertThat(updates).doesNotContainKey(SupportState.ORDER_ID);
        assertThat(updates).doesNotContainKey(SupportState.CANDIDATES);
    }

    @Test
    @DisplayName("Menu choice: in ORDER_CHOICE the chosen order is proposed and the question is closed (verify confirms it)")
    void menuChoiceResolvesOrder() {
        decision(new RouterDecision("TRACKING", 789L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.AWAITING, "ORDER_CHOICE",
                SupportState.CANDIDATES, List.of("#123 Portátil (IN_DELIVERY)", "#789 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry(SupportState.PROPOSED_ORDER_ID, 789L);
        assertThat(updates).doesNotContainKey(SupportState.ORDER_ID);
        assertThat(updates).containsEntry(SupportState.AWAITING, "NOTHING");
    }

    @Test
    @DisplayName("Back to list: BACK_TO_LIST releases the focus, drops the menu and reroutes to the list")
    void backToListReleasesFocus() {
        decision(new RouterDecision("OTHER", null, "NONE", "BACK_TO_LIST"));

        Map<String, Object> updates = router.apply(state(Map.of(SupportState.ORDER_ID, 789L, SupportState.INTENT, "TRACKING")));

        assertThat(updates).containsEntry(SupportState.ORDER_ID, AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry(SupportState.CANDIDATES, AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry(SupportState.AWAITING, "NOTHING");

        assertThat(updates).containsEntry(SupportState.INTENT, "TRACKING");
    }

    @Test
    @DisplayName("Back to list during a cancellation keeps the CANCEL intent")
    void backToListKeepsCancelIntent() {
        decision(new RouterDecision("OTHER", null, "NONE", "BACK_TO_LIST"));

        Map<String, Object> updates = router.apply(state(Map.of(SupportState.ORDER_ID, 789L, SupportState.INTENT, "CANCEL")));

        assertThat(updates).containsEntry(SupportState.ORDER_ID, AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry(SupportState.INTENT, "CANCEL");
    }

    @Test
    @DisplayName("Back to main menu: BACK_TO_MENU releases the focus and always falls to OTHER (fallback)")
    void backToMenuReleasesFocusAndFallsBackToOther() {
        decision(new RouterDecision("TRACKING", null, "NONE", "BACK_TO_MENU"));

        Map<String, Object> updates = router.apply(state(Map.of(SupportState.ORDER_ID, 789L, SupportState.INTENT, "TRACKING")));

        assertThat(updates).containsEntry(SupportState.ORDER_ID, AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry(SupportState.CANDIDATES, AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry(SupportState.TICKET_DRAFT, AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry(SupportState.AWAITING, "NOTHING");

        assertThat(updates).containsEntry(SupportState.INTENT, "OTHER");
    }

    @Test
    @DisplayName("'back' (BACK) with an order in focus: goes up one level to the list")
    void backWithFocusGoesToList() {
        decision(new RouterDecision("TRACKING", null, "NONE", "BACK"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, "atrás",
                SupportState.ORDER_ID, 789L,
                SupportState.INTENT, "TRACKING")));

        assertThat(updates).containsEntry(SupportState.ORDER_ID, AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry(SupportState.AWAITING, "NOTHING");

        assertThat(updates).containsEntry(SupportState.INTENT, "TRACKING");
    }

    @Test
    @DisplayName("'back' (BACK) when already on the list (no focus): goes up to the menu, does NOT re-list")
    void backOnListWithoutFocusGoesToMenu() {
        decision(new RouterDecision("OTHER", null, "NONE", "BACK"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, "atrás",
                SupportState.AWAITING, "ORDER_CHOICE",
                SupportState.INTENT, "TRACKING",
                SupportState.CANDIDATES, List.of("#123 Portátil (IN_DELIVERY)", "#789 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry(SupportState.ORDER_ID, AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry(SupportState.CANDIDATES, AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry(SupportState.AWAITING, "NOTHING");

        assertThat(updates).containsEntry(SupportState.INTENT, "OTHER");
    }

    @Test
    @DisplayName("'back' (BACK) while starting a return with an uncommitted deferred focus: returns to the list, NOT the menu")
    void backWithDeferredFocusDroppedGoesToList() {
        decision(new RouterDecision("OTHER", null, "NONE", "BACK"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, "atrás",
                SupportState.AWAITING, "NOTHING",
                SupportState.INTENT, "RETURN",
                SupportState.CANDIDATES, List.of("#123 Portátil (DELIVERED)", "#789 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry(SupportState.ORDER_ID, AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry(SupportState.CANDIDATES, AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry(SupportState.AWAITING, "NOTHING");

        assertThat(updates).containsEntry(SupportState.INTENT, "RETURN");
    }

    @Test
    @DisplayName("Digression: an FAQ with a pending request suspends the task WITHOUT touching its context")
    void faqDuringPendingTaskSuspendsWithoutTouchingContext() {
        decision(new RouterDecision("FAQ", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.INTENT, "TRACKING",
                SupportState.AWAITING, "ORDER_CHOICE",
                SupportState.CANDIDATES, List.of("#123 Portátil (IN_DELIVERY)", "#456 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry(SupportState.INTENT, "FAQ");

        assertThat(updates).containsEntry(SupportState.SUSPENDED_INTENT, "TRACKING");
        assertThat(updates).doesNotContainKey(SupportState.ORDER_ID);
        assertThat(updates).doesNotContainKey(SupportState.CANDIDATES);
        assertThat(updates).doesNotContainKey(SupportState.AWAITING);
    }

    @Test
    @DisplayName("Digression: an FAQ with no pending request suspends nothing (plain FAQ)")
    void faqWithoutPendingTaskIsNotDigression() {
        decision(new RouterDecision("FAQ", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(SupportState.AWAITING, "NOTHING")));

        assertThat(updates).containsEntry(SupportState.INTENT, "FAQ");
        assertThat(updates).doesNotContainKey(SupportState.SUSPENDED_INTENT);
    }

    @Test
    @DisplayName("Resume: picking the request up after the FAQ restores the intent and clears the suspension")
    void resumingAfterDigressionClearsSuspendedIntent() {
        decision(new RouterDecision("TRACKING", 123L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.INTENT, "FAQ",
                SupportState.SUSPENDED_INTENT, "TRACKING",
                SupportState.AWAITING, "ORDER_CHOICE",
                SupportState.CANDIDATES, List.of("#123 Portátil (IN_DELIVERY)", "#456 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry(SupportState.SUSPENDED_INTENT, AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry(SupportState.INTENT, "TRACKING");

        assertThat(updates).containsEntry(SupportState.PROPOSED_ORDER_ID, 123L);
        assertThat(updates).containsEntry(SupportState.AWAITING, "NOTHING");
    }

    @Test
    @DisplayName("Confirm button: the command resolves to YES deterministically, without the LLM")
    void confirmCancelCommandIsDeterministic() {
        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, UiAction.confirmCancel(200L),
                SupportState.AWAITING, "CANCEL_CONFIRM",
                SupportState.ORDER_ID, 200L)));

        assertThat(updates).containsEntry(SupportState.INTENT, "CANCEL");
        assertThat(updates).containsEntry(SupportState.ORDER_ID, 200L);
        assertThat(updates).containsEntry(SupportState.CONFIRMATION, "YES");
        verifyNoInteractions(classifier);
    }

    @Test
    @DisplayName("Confirm button without a pending confirmation: it does not count as YES")
    void confirmCommandWithoutPendingConfirmationIsIgnored() {
        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, UiAction.confirmCancel(200L))));

        assertThat(updates).containsEntry(SupportState.INTENT, "CANCEL");
        assertThat(updates).containsEntry(SupportState.CONFIRMATION, "NONE");
        verifyNoInteractions(classifier);
    }

    @Test
    @DisplayName("Confirm button for another order than the pending one: it does not count as YES")
    void confirmCommandForAnotherOrderIsIgnored() {
        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, UiAction.confirmCancel(201L),
                SupportState.AWAITING, "CANCEL_CONFIRM",
                SupportState.ORDER_ID, 200L)));

        assertThat(updates).containsEntry(SupportState.ORDER_ID, 201L);
        assertThat(updates).containsEntry(SupportState.CONFIRMATION, "NONE");
    }

    @Test
    @DisplayName("Return confirm button while a cancellation is pending: it does not count as YES")
    void returnConfirmDuringCancelConfirmationIsIgnored() {
        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, UiAction.confirmReturn(200L),
                SupportState.AWAITING, "CANCEL_CONFIRM",
                SupportState.ORDER_ID, 200L)));

        assertThat(updates).containsEntry(SupportState.INTENT, "RETURN");
        assertThat(updates).containsEntry(SupportState.CONFIRMATION, "NONE");
    }

    @Test
    @DisplayName("Dismiss button: the command resolves to NO deterministically, without the LLM")
    void abortCancelCommandIsDeterministic() {
        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, UiAction.abortCancel(200L),
                SupportState.AWAITING, "CANCEL_CONFIRM",
                SupportState.ORDER_ID, 200L)));

        assertThat(updates).containsEntry(SupportState.CONFIRMATION, "NO");
        assertThat(updates).containsEntry(SupportState.ORDER_ID, 200L);
        verifyNoInteractions(classifier);
    }

    @Test
    @DisplayName("Card selection '#id': sets the focus without the LLM when the id is on the menu")
    void cardSelectionResolvesFocusDeterministically() {
        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, "#123",
                SupportState.AWAITING, "ORDER_CHOICE",
                SupportState.CANDIDATES, List.of("#123 Portátil (IN_DELIVERY)", "#789 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry(SupportState.ORDER_ID, 123L);
        assertThat(updates).containsEntry(SupportState.AWAITING, "NOTHING");
        verifyNoInteractions(classifier);
    }

    @Test
    @DisplayName("Chatty digression: small talk in the middle of a request suspends it like an FAQ")
    void chattyDuringPendingTaskSuspends() {
        decision(new RouterDecision("CHATTY", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.INTENT, "TRACKING",
                SupportState.AWAITING, "ORDER_CHOICE",
                SupportState.CANDIDATES, List.of("#123 Portátil (IN_DELIVERY)"))));

        assertThat(updates).containsEntry(SupportState.INTENT, "CHATTY");
        assertThat(updates).containsEntry(SupportState.SUSPENDED_INTENT, "TRACKING");
        assertThat(updates).doesNotContainKey(SupportState.ORDER_ID);
        assertThat(updates).doesNotContainKey(SupportState.AWAITING);
    }

    @Test
    @DisplayName("Clarification: an ambiguous OTHER with focus increments the counter; without focus it resets it")
    void clarifyAttemptsTrackOnlyWithContext() {
        decision(new RouterDecision("OTHER", null, "NONE", "NONE"));

        Map<String, Object> withFocus = router.apply(state(Map.of(
                SupportState.INTENT, "TRACKING", SupportState.ORDER_ID, 789L, SupportState.CLARIFY_ATTEMPTS, 1)));
        assertThat(withFocus).containsEntry(SupportState.CLARIFY_ATTEMPTS, 2);

        Map<String, Object> noFocus = router.apply(state(Map.of(SupportState.CLARIFY_ATTEMPTS, 3)));
        assertThat(noFocus).containsEntry(SupportState.CLARIFY_ATTEMPTS, 0);
    }

    @Test
    @DisplayName("SUPPORT: the orderRelated slot is copied to the state (it decides whether the order is identified)")
    void supportOrderRelatedIsDumped() {
        decision(new RouterDecision("SUPPORT", null, "NONE", "NONE", true));
        assertThat(router.apply(state(Map.of())))
                .containsEntry(SupportState.INTENT, "SUPPORT")
                .containsEntry(SupportState.ORDER_RELATED, true);

        decision(new RouterDecision("SUPPORT", null, "NONE", "NONE", false));
        assertThat(router.apply(state(Map.of())))
                .containsEntry(SupportState.ORDER_RELATED, false);
    }

    @Test
    @DisplayName("orderRelated is only copied for SUPPORT; other intents leave it alone")
    void orderRelatedOnlyForSupport() {
        decision(new RouterDecision("TRACKING", 123L, "NONE", "NONE", true));
        assertThat(router.apply(state(Map.of()))).doesNotContainKey(SupportState.ORDER_RELATED);
    }

    @Test
    @DisplayName("Ticket gate: if the user stays on SUPPORT, the draft is NOT dropped")
    void ticketGateKeepsDraftWhenStillSupport() {
        decision(new RouterDecision("SUPPORT", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.INTENT, "SUPPORT", SupportState.AWAITING, "TICKET_INFO", SupportState.TICKET_DRAFT, "el monitor llegó roto")));

        assertThat(updates).containsEntry(SupportState.INTENT, "SUPPORT");

        assertThat(updates).doesNotContainKey(SupportState.TICKET_DRAFT);
        assertThat(updates).doesNotContainEntry(SupportState.AWAITING, "NOTHING");
    }

    @Test
    @DisplayName("Ticket gate: if the user asks for something else, the draft is dropped and rerouted")
    void ticketGateAbandonedWhenUserWantsSomethingElse() {
        decision(new RouterDecision("OTHER", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.INTENT, "SUPPORT", SupportState.AWAITING, "TICKET_INFO", SupportState.TICKET_DRAFT, "tengo un problema")));

        assertThat(updates).containsEntry(SupportState.TICKET_DRAFT, AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry(SupportState.AWAITING, "NOTHING");
        assertThat(updates).containsEntry(SupportState.INTENT, "OTHER");

        assertThat(updates).containsEntry(SupportState.CLARIFY_ATTEMPTS, 0);
    }

    @Test
    @DisplayName("Ticket gate: pivoting to cancel another order drops the draft and PROPOSES the focus (verify confirms it)")
    void ticketGatePivotToCancelDropsDraftAndSwitchesOrder() {
        decision(new RouterDecision("CANCEL", 200L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.INTENT, "SUPPORT", SupportState.AWAITING, "TICKET_INFO", SupportState.TICKET_DRAFT, "esto va fatal")));

        assertThat(updates).containsEntry(SupportState.TICKET_DRAFT, AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry(SupportState.AWAITING, "NOTHING");
        assertThat(updates).containsEntry(SupportState.INTENT, "CANCEL");

        assertThat(updates).containsEntry(SupportState.PROPOSED_ORDER_ID, 200L);
        assertThat(updates).doesNotContainKey(SupportState.ORDER_ID);
    }

    @Test
    @DisplayName("Ticket gate: a side FAQ SUSPENDS it (does not abandon it): the draft survives")
    void ticketGateFaqDigressionSuspendsWithoutDroppingDraft() {
        decision(new RouterDecision("FAQ", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.INTENT, "SUPPORT", SupportState.AWAITING, "TICKET_INFO", SupportState.TICKET_DRAFT, "el monitor llegó roto")));

        assertThat(updates).containsEntry(SupportState.INTENT, "FAQ");
        assertThat(updates).containsEntry(SupportState.SUSPENDED_INTENT, "SUPPORT");

        assertThat(updates).doesNotContainKey(SupportState.TICKET_DRAFT);
    }

    @Test
    @DisplayName("An '#id' not on the menu is no shortcut: the LLM resolves it (it is not set blindly)")
    void unknownCardIdFallsThroughToLlm() {
        decision(new RouterDecision("TRACKING", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, "#999",
                SupportState.AWAITING, "ORDER_CHOICE",
                SupportState.CANDIDATES, List.of("#123 Portátil (IN_DELIVERY)"))));

        assertThat(updates).doesNotContainEntry(SupportState.ORDER_ID, 999L);
    }

    @Test
    @DisplayName("Bare assent with nothing awaited: a forged action becomes OTHER and drops the order and the confirmation")
    void bareAssentWithNothingAwaitedTriggersNoAction() {
        decision(new RouterDecision("CANCEL", 201L, "YES", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(SupportState.USER_MESSAGE, "vale", SupportState.AWAITING, "NOTHING")));

        assertThat(updates).containsEntry(SupportState.INTENT, "OTHER");
        assertThat(updates).doesNotContainKeys(SupportState.ORDER_ID, SupportState.PROPOSED_ORDER_ID, SupportState.CONFIRMATION);
    }

    @Test
    @DisplayName("Bare assent to the action the system offered while clarifying: the action goes on and the offer is kept for routing")
    void bareAssentAcceptsTheOfferedAction() {
        decision(new RouterDecision("OTHER", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, "sí", SupportState.INTENT, "CANCEL", SupportState.ORDER_ID, 789L, SupportState.AWAITING, "NOTHING",
                SupportState.OFFERED_INTENT, "CANCEL")));

        assertThat(updates).containsEntry(SupportState.INTENT, "CANCEL");
        assertThat(updates).doesNotContainKeys(SupportState.ORDER_ID, SupportState.OFFERED_INTENT);
        assertThat(updates).containsEntry(SupportState.CLARIFY_ATTEMPTS, 0);
    }

    @Test
    @DisplayName("Any other answer to an offer withdraws it")
    void otherAnswerWithdrawsTheOffer() {
        decision(new RouterDecision("TRACKING", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, "mejor dime cuándo llega", SupportState.INTENT, "CANCEL", SupportState.ORDER_ID, 789L,
                SupportState.OFFERED_INTENT, "CANCEL")));

        assertThat(updates).containsEntry(SupportState.INTENT, "TRACKING");
        assertThat(updates).containsEntry(SupportState.OFFERED_INTENT, "NONE");
    }

    @Test
    @DisplayName("Bare assent while a cancellation is really awaited is still a YES")
    void bareAssentConfirmsARealPendingCancellation() {
        decision(new RouterDecision("CANCEL", null, "YES", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, "vale", SupportState.INTENT, "CANCEL", SupportState.ORDER_ID, 200L, SupportState.AWAITING, "CANCEL_CONFIRM")));

        assertThat(updates).containsEntry(SupportState.INTENT, "CANCEL");
        assertThat(updates).containsEntry(SupportState.CONFIRMATION, "YES");
    }

    @Test
    @DisplayName("An assent that carries its own request is not downgraded")
    void assentWithItsOwnRequestIsKept() {
        decision(new RouterDecision("CANCEL", 201L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, "cancela el pedido 201, sí", SupportState.AWAITING, "NOTHING")));

        assertThat(updates).containsEntry(SupportState.INTENT, "CANCEL");
        assertThat(updates).containsEntry(SupportState.PROPOSED_ORDER_ID, 201L);
    }

    @Test
    @DisplayName("A typed YES naming another order does not confirm: that order gets its own confirmation card")
    void typedYesForAnotherOrderIsNotAConfirmation() {
        decision(new RouterDecision("CANCEL", 207L, "YES", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, "sí, el 207", SupportState.INTENT, "CANCEL", SupportState.ORDER_ID, 200L, SupportState.AWAITING, "CANCEL_CONFIRM")));

        assertThat(updates).containsEntry(SupportState.ORDER_ID, 207L);
        assertThat(updates).containsEntry(SupportState.CONFIRMATION, "NONE");
    }

    @Test
    @DisplayName("A typed YES for another action than the pending one does not confirm")
    void typedYesForAnotherActionIsNotAConfirmation() {
        decision(new RouterDecision("RETURN", null, "YES", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, "sí, devuélvelo", SupportState.INTENT, "CANCEL", SupportState.ORDER_ID, 200L, SupportState.AWAITING, "CANCEL_CONFIRM")));

        assertThat(updates).containsEntry(SupportState.INTENT, "RETURN");
        assertThat(updates).containsEntry(SupportState.CONFIRMATION, "NONE");
    }

    @Test
    @DisplayName("A ticket draft left by a doubtful SUPPORT is dropped when the user moves on")
    void staleTicketDraftIsDroppedOnAnotherIntent() {
        decision(new RouterDecision("CANCEL", 201L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, "cancela el 201", SupportState.AWAITING, "NOTHING", SupportState.TICKET_DRAFT, "esto va fatal")));

        assertThat(updates).containsEntry(SupportState.TICKET_DRAFT, AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry(SupportState.PROPOSED_ORDER_ID, 201L);
    }

    @Test
    @DisplayName("An unparseable router output degrades to OTHER instead of failing the turn")
    void unparseableDecisionDegradesToOther() {
        when(classifier.classify(any(), any(), any(), any(), any(), any()))
                .thenThrow(new OutputParsingException("not JSON", null));

        Map<String, Object> updates = router.apply(state(Map.of(
                SupportState.USER_MESSAGE, "cancela", SupportState.ORDER_ID, 200L, SupportState.AWAITING, "CANCEL_CONFIRM", SupportState.INTENT, "CANCEL")));

        assertThat(updates).containsEntry(SupportState.INTENT, "OTHER");
        assertThat(updates).containsEntry(SupportState.CONFIRMATION, "NONE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OTHER", "TICKETS", "FAQ", "CHATTY"})
    @DisplayName("An informational intent never turns an orderId into the focus the next turn inherits")
    void informationalIntentDoesNotSetFocus(String intent) {
        decision(new RouterDecision(intent, 305L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(SupportState.AWAITING, "NOTHING")));

        assertThat(updates).containsEntry(SupportState.INTENT, intent);
        assertThat(updates).doesNotContainKeys(SupportState.ORDER_ID, SupportState.PROPOSED_ORDER_ID, SupportState.CANDIDATES);
    }

    @Test
    @DisplayName("SUPPORT keeps setting the focus: the ticket needs the order it is about")
    void supportStillSetsFocus() {
        decision(new RouterDecision("SUPPORT", 382L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(SupportState.AWAITING, "NOTHING")));

        assertThat(updates).containsEntry(SupportState.ORDER_ID, 382L);
    }
}
