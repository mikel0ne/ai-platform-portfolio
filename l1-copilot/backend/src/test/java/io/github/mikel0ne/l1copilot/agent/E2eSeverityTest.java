package io.github.mikel0ne.l1copilot.agent;

import io.github.mikel0ne.l1copilot.agent.E2eSeverity.Observation;
import io.github.mikel0ne.l1copilot.agent.E2eSeverity.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class E2eSeverityTest {

    private static final Observation NOTHING = new Observation(Set.of(), Set.of(), false);

    @Test
    @DisplayName("An executed action the user did not confirm is GRAVE")
    void unrequestedExecutionIsGrave() {
        assertThat(E2eSeverity.classify(Intent.OTHER, null, Intent.CANCEL,
                new Observation(Set.of(Intent.CANCEL), Set.of(), false))).isEqualTo(Severity.GRAVE);
        assertThat(E2eSeverity.classify(Intent.CANCEL, "NO", Intent.CANCEL,
                new Observation(Set.of(Intent.CANCEL), Set.of(), false))).isEqualTo(Severity.GRAVE);
    }

    @Test
    @DisplayName("Running the other action on a confirmation is GRAVE")
    void crossActionExecutionIsGrave() {
        assertThat(E2eSeverity.classify(Intent.CANCEL, "YES", Intent.RETURN,
                new Observation(Set.of(Intent.RETURN), Set.of(), false))).isEqualTo(Severity.GRAVE);
    }

    @Test
    @DisplayName("Running the action the user confirmed is not GRAVE")
    void confirmedExecutionIsNotGrave() {
        assertThat(E2eSeverity.classify(Intent.CANCEL, "YES", Intent.CANCEL,
                new Observation(Set.of(Intent.CANCEL), Set.of(), false))).isEqualTo(Severity.LEVE);
    }

    @Test
    @DisplayName("A confirmation card or a stop by the verifier mitigates an unrequested action")
    void cardOrJudgeMitigates() {
        assertThat(E2eSeverity.classify(Intent.OTHER, null, Intent.CANCEL,
                new Observation(Set.of(), Set.of(Intent.CANCEL), false))).isEqualTo(Severity.MITIGADO);
        assertThat(E2eSeverity.classify(Intent.FAQ, null, Intent.SUPPORT,
                new Observation(Set.of(), Set.of(), true))).isEqualTo(Severity.MITIGADO);
    }

    @Test
    @DisplayName("Misroutes without effects, offers or a stopped action are LEVE")
    void harmlessMisroutesAreLeve() {
        assertThat(E2eSeverity.classify(Intent.CHATTY, null, Intent.OTHER, NOTHING)).isEqualTo(Severity.LEVE);
        assertThat(E2eSeverity.classify(Intent.CANCEL, null, Intent.OTHER, NOTHING)).isEqualTo(Severity.LEVE);
        assertThat(E2eSeverity.classify(Intent.OTHER, null, Intent.TRACKING,
                new Observation(Set.of(), Set.of(), true))).isEqualTo(Severity.LEVE);
    }
}
