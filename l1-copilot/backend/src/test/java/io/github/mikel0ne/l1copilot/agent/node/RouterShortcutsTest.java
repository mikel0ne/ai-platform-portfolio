package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.UiAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RouterShortcutsTest {

    private SupportState state(Map<String, Object> data) {
        return new SupportState(new HashMap<>(data));
    }

    @Test
    @DisplayName("The abort-return button dismisses the return of that order without the LLM")
    void abortReturnIsResolvedInCode() {
        Optional<Map<String, Object>> result = RouterShortcuts.resolve(
                state(Map.of(SupportState.USER_MESSAGE, UiAction.abortReturn(42))));

        assertThat(result).isPresent();
        assertThat(result.get())
                .containsEntry(SupportState.INTENT, "RETURN")
                .containsEntry(SupportState.ORDER_ID, 42L)
                .containsEntry(SupportState.CONFIRMATION, "NO")
                .containsEntry(SupportState.CLARIFY_ATTEMPTS, 0);
    }

    @Test
    @DisplayName("A bare #id outside the order choice is not a shortcut")
    void bareOrderIdWithoutChoiceIsNotAShortcut() {
        Optional<Map<String, Object>> result = RouterShortcuts.resolve(state(Map.of(
                SupportState.USER_MESSAGE, "#123",
                SupportState.AWAITING, "NOTHING",
                SupportState.CANDIDATES, List.of("#123 Portátil (X)"))));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("A normal message is not a shortcut")
    void normalMessageIsNotAShortcut() {
        assertThat(RouterShortcuts.resolve(state(Map.of(SupportState.USER_MESSAGE, "hola")))).isEmpty();
    }
}
