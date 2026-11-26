package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Intent;
import io.github.mikel0ne.l1copilot.agent.Navigation;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RouterNavigationTest {

    private SupportState state(Map<String, Object> data) {
        return new SupportState(new HashMap<>(data));
    }

    @Test
    @DisplayName("Without a navigation request there is nothing to resolve")
    void noNavigationIsEmpty() {
        assertThat(RouterNavigation.resolve(Navigation.NONE, Intent.TRACKING, state(Map.of()))).isEmpty();
    }

    @Test
    @DisplayName("A bare BACK with a focused order goes up to the list")
    void backWithFocusGoesToList() {
        assertThat(RouterNavigation.destination(Navigation.BACK, state(Map.of(SupportState.ORDER_ID, 7L))))
                .isEqualTo(Navigation.BACK_TO_LIST);
    }

    @Test
    @DisplayName("A bare BACK with a shown list and nothing awaited goes up to the list")
    void backWithListGoesToList() {
        SupportState state = state(Map.of(
                SupportState.CANDIDATES, List.of("#1 A (X)"), SupportState.AWAITING, "NOTHING"));

        assertThat(RouterNavigation.destination(Navigation.BACK, state)).isEqualTo(Navigation.BACK_TO_LIST);
    }

    @Test
    @DisplayName("A bare BACK while choosing from the list goes up to the menu")
    void backWhileChoosingGoesToMenu() {
        SupportState state = state(Map.of(
                SupportState.CANDIDATES, List.of("#1 A (X)"), SupportState.AWAITING, "ORDER_CHOICE"));

        assertThat(RouterNavigation.destination(Navigation.BACK, state)).isEqualTo(Navigation.BACK_TO_MENU);
    }

    @Test
    @DisplayName("A bare BACK with nothing below the menu stays at the menu")
    void backWithNothingGoesToMenu() {
        assertThat(RouterNavigation.destination(Navigation.BACK, state(Map.of())))
                .isEqualTo(Navigation.BACK_TO_MENU);
    }

    @Test
    @DisplayName("An explicit destination passes through")
    void explicitDestinationPassesThrough() {
        SupportState state = state(Map.of(SupportState.ORDER_ID, 7L));

        assertThat(RouterNavigation.destination(Navigation.BACK_TO_LIST, state)).isEqualTo(Navigation.BACK_TO_LIST);
        assertThat(RouterNavigation.destination(Navigation.BACK_TO_MENU, state)).isEqualTo(Navigation.BACK_TO_MENU);
    }

    @Test
    @DisplayName("Back to the list keeps the action of the turn when it is an order action")
    void targetKeepsTheTurnAction() {
        assertThat(RouterNavigation.targetForBackToList(Intent.TRACKING, Intent.CANCEL)).isEqualTo(Intent.TRACKING);
        assertThat(RouterNavigation.targetForBackToList(Intent.CANCEL, Intent.RETURN)).isEqualTo(Intent.CANCEL);
        assertThat(RouterNavigation.targetForBackToList(Intent.RETURN, Intent.TRACKING)).isEqualTo(Intent.RETURN);
    }

    @Test
    @DisplayName("Back to the list falls back to the current action, then to tracking")
    void targetFallsBackToCurrentAction() {
        assertThat(RouterNavigation.targetForBackToList(Intent.OTHER, Intent.CANCEL)).isEqualTo(Intent.CANCEL);
        assertThat(RouterNavigation.targetForBackToList(Intent.OTHER, Intent.RETURN)).isEqualTo(Intent.RETURN);
        assertThat(RouterNavigation.targetForBackToList(Intent.OTHER, Intent.SUPPORT)).isEqualTo(Intent.TRACKING);
    }

    @Test
    @DisplayName("Back to the menu releases the whole context and goes to OTHER")
    void backToMenuReleasesEverything() {
        Map<String, Object> updates = RouterNavigation.updates(Navigation.BACK_TO_MENU, Intent.TRACKING, Intent.CANCEL);

        assertThat(updates).containsOnlyKeys(
                SupportState.ORDER_ID, SupportState.CANDIDATES, SupportState.TICKET_DRAFT,
                SupportState.SUSPENDED_INTENT, SupportState.AWAITING, SupportState.CLARIFY_ATTEMPTS,
                SupportState.NAVIGATING, SupportState.INTENT);
        assertThat(updates)
                .containsEntry(SupportState.ORDER_ID, AgentState.MARK_FOR_REMOVAL)
                .containsEntry(SupportState.CANDIDATES, AgentState.MARK_FOR_REMOVAL)
                .containsEntry(SupportState.TICKET_DRAFT, AgentState.MARK_FOR_REMOVAL)
                .containsEntry(SupportState.SUSPENDED_INTENT, AgentState.MARK_FOR_REMOVAL)
                .containsEntry(SupportState.AWAITING, "NOTHING")
                .containsEntry(SupportState.CLARIFY_ATTEMPTS, 0)
                .containsEntry(SupportState.NAVIGATING, true)
                .containsEntry(SupportState.INTENT, "OTHER");
    }

    @Test
    @DisplayName("Resolving a navigation uses the intent of the state, not a suspended one, as the current action")
    void resolveUsesTheStateIntent() {
        SupportState state = state(Map.of(
                SupportState.INTENT, "RETURN", SupportState.SUSPENDED_INTENT, "CANCEL", SupportState.ORDER_ID, 7L));

        Optional<Map<String, Object>> result = RouterNavigation.resolve(Navigation.BACK, Intent.OTHER, state);

        assertThat(result).isPresent();
        assertThat(result.get()).containsEntry(SupportState.INTENT, "RETURN");
    }
}
