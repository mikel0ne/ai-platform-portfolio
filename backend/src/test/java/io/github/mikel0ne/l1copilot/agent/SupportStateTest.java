package io.github.mikel0ne.l1copilot.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SupportStateTest {

    @Test
    @DisplayName("Normaliza candidatos antiguos a texto plano compatible con el restart classloader")
    void candidatesAreNormalizedFromLegacyObjects() {
        SupportState state = new SupportState(Map.of(
                "candidates", List.of(new LegacyCandidate(123L, "Portátil", "IN_DELIVERY", LocalDate.of(2026, 6, 1)))
        ));

        assertThat(state.candidates())
                .containsExactly("#123 Portátil (IN_DELIVERY, pedido el 2026-06-01)");
    }

    private static final class LegacyCandidate {
        private final Long id;
        private final String description;
        private final String status;
        private final LocalDate orderDate;

        private LegacyCandidate(Long id, String description, String status, LocalDate orderDate) {
            this.id = id;
            this.description = description;
            this.status = status;
            this.orderDate = orderDate;
        }

        public Long id() {
            return id;
        }

        public String description() {
            return description;
        }

        public String status() {
            return status;
        }

        public LocalDate orderDate() {
            return orderDate;
        }
    }
}
