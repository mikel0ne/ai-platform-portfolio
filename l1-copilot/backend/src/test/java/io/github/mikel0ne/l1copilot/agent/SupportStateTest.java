package io.github.mikel0ne.l1copilot.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SupportStateTest {

    @Test
    @DisplayName("Devuelve los candidatos como texto plano tal cual se guardaron")
    void candidatesAreReturnedAsStoredText() {
        SupportState state = new SupportState(Map.of(
                "candidates", List.of(
                        "#123 Portátil (IN_DELIVERY, pedido el 2026-06-01)",
                        "#456 Ratón (PAID, pedido el 2026-06-20)")
        ));

        assertThat(state.candidates())
                .containsExactly(
                        "#123 Portátil (IN_DELIVERY, pedido el 2026-06-01)",
                        "#456 Ratón (PAID, pedido el 2026-06-20)");
    }

    @Test
    @DisplayName("Sin candidatos en el estado, devuelve lista vacía (no null)")
    void candidatesDefaultToEmptyList() {
        SupportState state = new SupportState(Map.of());

        assertThat(state.candidates()).isEmpty();
    }
}
