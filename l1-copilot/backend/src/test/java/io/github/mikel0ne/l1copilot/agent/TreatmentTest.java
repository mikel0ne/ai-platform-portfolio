package io.github.mikel0ne.l1copilot.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** El registro (tú/usted) se decide por la edad con un umbral; parseo tolerante. */
class TreatmentTest {

    @Test
    @DisplayName("A partir del umbral se trata de usted; por debajo se tutea")
    void ageDecidesTreatment() {
        assertThat(Treatment.forAge(28, 60)).isEqualTo(Treatment.TU);
        assertThat(Treatment.forAge(59, 60)).isEqualTo(Treatment.TU);
        assertThat(Treatment.forAge(60, 60)).isEqualTo(Treatment.USTED);
        assertThat(Treatment.forAge(70, 60)).isEqualTo(Treatment.USTED);
        // Sin edad conocida, por defecto tuteo
        assertThat(Treatment.forAge(null, 60)).isEqualTo(Treatment.TU);
    }

    @Test
    @DisplayName("fromState es tolerante y cae a TU ante valores ausentes o inválidos")
    void fromStateIsLenient() {
        assertThat(Treatment.fromState("USTED")).isEqualTo(Treatment.USTED);
        assertThat(Treatment.fromState("tu")).isEqualTo(Treatment.TU);
        assertThat(Treatment.fromState(null)).isEqualTo(Treatment.TU);
        assertThat(Treatment.fromState("???")).isEqualTo(Treatment.TU);
    }
}
