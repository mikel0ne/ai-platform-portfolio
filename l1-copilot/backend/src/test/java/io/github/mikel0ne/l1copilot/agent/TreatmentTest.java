package io.github.mikel0ne.l1copilot.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TreatmentTest {

    @Test
    @DisplayName("From the age threshold up the user is addressed formally (usted); below it informally (tú)")
    void ageDecidesTreatment() {
        assertThat(Treatment.forAge(28, 60)).isEqualTo(Treatment.TU);
        assertThat(Treatment.forAge(59, 60)).isEqualTo(Treatment.TU);
        assertThat(Treatment.forAge(60, 60)).isEqualTo(Treatment.USTED);
        assertThat(Treatment.forAge(70, 60)).isEqualTo(Treatment.USTED);

        assertThat(Treatment.forAge(null, 60)).isEqualTo(Treatment.TU);
    }

    @Test
    @DisplayName("fromState is lenient and falls back to TU on missing or invalid values")
    void fromStateIsLenient() {
        assertThat(Treatment.fromState("USTED")).isEqualTo(Treatment.USTED);
        assertThat(Treatment.fromState("tu")).isEqualTo(Treatment.TU);
        assertThat(Treatment.fromState(null)).isEqualTo(Treatment.TU);
        assertThat(Treatment.fromState("???")).isEqualTo(Treatment.TU);
    }
}
