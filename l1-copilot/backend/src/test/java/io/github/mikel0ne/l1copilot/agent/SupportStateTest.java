package io.github.mikel0ne.l1copilot.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SupportStateTest {

    @Test
    @DisplayName("Returns the candidates as plain text exactly as stored")
    void candidatesAreReturnedAsStoredText() {
        SupportState state = new SupportState(Map.of(
                SupportState.CANDIDATES, List.of(
                        "#123 Portátil (IN_DELIVERY, pedido el 2026-06-01)",
                        "#456 Ratón (PAID, pedido el 2026-06-20)")
        ));

        assertThat(state.candidates())
                .containsExactly(
                        "#123 Portátil (IN_DELIVERY, pedido el 2026-06-01)",
                        "#456 Ratón (PAID, pedido el 2026-06-20)");
    }

    @Test
    @DisplayName("Without candidates in the state, returns an empty list (not null)")
    void candidatesDefaultToEmptyList() {
        SupportState state = new SupportState(Map.of());

        assertThat(state.candidates()).isEmpty();
    }

    @Test
    @DisplayName("Without a verification in the turn there is no verdict, not a made-up SURE")
    void verdictDefaultsToNone() {
        assertThat(new SupportState(Map.of()).verdict()).isEqualTo(Verdict.NONE);
    }

    @Test
    @DisplayName("Every turn starts with no verdict, so a previous one is never reported again")
    void turnResetsClearTheVerdict() {
        SupportState state = new SupportState(new java.util.HashMap<>(SupportState.TURN_RESETS));

        assertThat(state.verdict()).isEqualTo(Verdict.NONE);
    }

    @Test
    @DisplayName("An Integer order id in the state is read as a Long, as a checkpoint round trip may leave it")
    void orderIdIsReadFromAnyNumber() {
        SupportState state = new SupportState(Map.of(SupportState.ORDER_ID, 123));

        assertThat(state.orderId()).isEqualTo(java.util.Optional.of(123L));
    }

    @Test
    @DisplayName("Without an order id in the state there is no focus")
    void orderIdDefaultsToEmpty() {
        assertThat(new SupportState(Map.of()).orderId()).isEmpty();
    }
}
