package io.github.mikel0ne.l1copilot.agent;

import io.github.mikel0ne.l1copilot.agent.RouterGoldenCases.GoldenCase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RouterGoldenCasesTest {

    private static final int MIN_CASES = 30;
    private static final int MIN_SECURITY_CASES = 10;
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final Set<Awaiting> CONFIRM_GATES =
            EnumSet.of(Awaiting.CANCEL_CONFIRM, Awaiting.RETURN_CONFIRM, Awaiting.TICKET_CONFIRM);

    private static List<GoldenCase> cases;

    @BeforeAll
    static void load() throws Exception {
        cases = RouterGoldenCases.load();
    }

    @Test
    @DisplayName("The golden set keeps its size and its share of security cases")
    void keepsSizeAndSecurityShare() {
        assertThat(cases).hasSizeGreaterThanOrEqualTo(MIN_CASES);
        assertThat(cases.stream().filter(GoldenCase::security).count())
                .isGreaterThanOrEqualTo(MIN_SECURITY_CASES);
    }

    @Test
    @DisplayName("Every message is non-blank, within the API size limit and unique")
    void messagesAreValidAndUnique() {
        Set<String> seen = new HashSet<>();
        for (GoldenCase c : cases) {
            assertThat(c.message()).as("message").isNotBlank();
            assertThat(c.message().length()).as("length of: %s", c.message()).isLessThanOrEqualTo(MAX_MESSAGE_LENGTH);
            assertThat(seen.add(c.message().strip().toLowerCase(Locale.ROOT)))
                    .as("duplicated message: %s", c.message()).isTrue();
        }
    }

    @Test
    @DisplayName("Every expected intent is a routable value of the real enum, spelled exactly")
    void expectedIntentsAreRealIntents() {
        for (GoldenCase c : cases) {
            assertThatCode(() -> Intent.valueOf(c.expectedIntent()))
                    .as("expectedIntent of: %s", c.message()).doesNotThrowAnyException();
            assertThat(Intent.valueOf(c.expectedIntent())).as("expectedIntent of: %s", c.message())
                    .isNotEqualTo(Intent.NONE);
        }
    }

    @Test
    @DisplayName("Every routable intent is expected by at least one case")
    void everyIntentIsCovered() {
        Set<Intent> expected = cases.stream().map(c -> Intent.valueOf(c.expectedIntent()))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Intent.class)));
        Set<Intent> routable = EnumSet.complementOf(EnumSet.of(Intent.NONE));

        assertThat(expected).containsAll(routable);
    }

    @Test
    @DisplayName("Every case has a known category and every category has at least one case")
    void categoriesAreKnownAndCovered() {
        Set<String> known = new HashSet<>(RouterGoldenCases.FUNCTIONAL_CATEGORIES);
        known.addAll(RouterGoldenCases.SECURITY_CATEGORIES);
        Map<String, Long> counts = cases.stream()
                .collect(Collectors.groupingBy(GoldenCase::category, Collectors.counting()));

        assertThat(counts.keySet()).as("categories in use").isSubsetOf(known);
        assertThat(counts.keySet()).as("categories without cases").containsAll(known);
    }

    @Test
    @DisplayName("Every security case explains why its expected intent is the right one")
    void securityCasesCarryARationale() {
        cases.stream().filter(GoldenCase::security).forEach(c ->
                assertThat(c.rationale()).as("rationale of: %s", c.message()).isNotBlank());
    }

    @Test
    @DisplayName("A jailbreak or injection is never expected to land on the intent it tries to force")
    void manipulationIsNeverExpectedToSucceed() {
        cases.stream()
                .filter(c -> RouterGoldenCases.FORCED_INTENT_CATEGORIES.contains(c.category()))
                .forEach(c -> Arrays.stream(Intent.values())
                        .map(Intent::name)
                        .filter(c.message()::contains)
                        .forEach(forced -> assertThat(c.expectedIntent())
                                .as("expected intent of: %s", c.message()).isNotEqualTo(forced)));
    }

    @Test
    @DisplayName("Only multi-turn categories carry a history; the ones about the history always do")
    void historyDefaultsAndHistoryCasesCarryOne() {
        cases.stream().filter(c -> !RouterGoldenCases.HISTORY_ALLOWED_CATEGORIES.contains(c.category())).forEach(c ->
                assertThat(c.history()).as("history of: %s", c.message()).isEqualTo(RouterGoldenCases.NO_HISTORY));
        cases.stream().filter(c -> RouterGoldenCases.HISTORY_REQUIRED_CATEGORIES.contains(c.category())).forEach(c ->
                assertThat(c.history()).as("history of: %s", c.message()).isNotEqualTo(RouterGoldenCases.NO_HISTORY));
        List<GoldenCase> historyCases = cases.stream()
                .filter(c -> "history-injection".equals(c.category())).toList();
        assertThat(historyCases).hasSizeGreaterThanOrEqualTo(5);
        historyCases.forEach(c ->
                assertThat(c.history()).as("history of: %s", c.message()).isNotEqualTo(RouterGoldenCases.NO_HISTORY));
        assertThat(historyCases.stream().map(c -> c.expectedIntent()).distinct().count())
                .as("benign cases with a non-OTHER intent that needs the history").isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("The held-out cases keep a meaningful share of the set and are mostly security cases")
    void heldoutCasesAreKept() {
        List<GoldenCase> heldout = cases.stream().filter(GoldenCase::heldout).toList();
        assertThat(heldout).hasSizeGreaterThanOrEqualTo(13);
        assertThat(heldout.stream().filter(GoldenCase::security).count()).isGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("A case state uses real enum values, a choice offers well-formed candidates and a confirmation only exists behind a gate")
    void statesAreRealAndConsistent() {
        for (GoldenCase c : cases) {
            RouterGoldenCases.State state = c.state();
            assertThatCode(() -> Awaiting.valueOf(state.awaiting())).as("awaiting of: %s", c.message())
                    .doesNotThrowAnyException();
            assertThatCode(() -> Intent.valueOf(state.currentIntent())).as("currentIntent of: %s", c.message())
                    .doesNotThrowAnyException();
            if (Awaiting.valueOf(state.awaiting()) == Awaiting.ORDER_CHOICE) {
                assertThat(state.candidates()).as("candidates of: %s", c.message()).isNotEmpty();
            }
            state.candidates().forEach(candidate ->
                    assertThat(candidate).as("candidate of: %s", c.message()).matches("#\\d+ .+"));
            if (c.expectedConfirmation() != null) {
                assertThatCode(() -> Confirmation.valueOf(c.expectedConfirmation()))
                        .as("expectedConfirmation of: %s", c.message()).doesNotThrowAnyException();
                assertThat(CONFIRM_GATES).as("awaiting of: %s", c.message())
                        .contains(Awaiting.valueOf(state.awaiting()));
            }
        }
    }

    @Test
    @DisplayName("Every confirmation gate has a case where a plain assent must still confirm")
    void everyConfirmationGateStillConfirms() {
        Set<Awaiting> confirmed = cases.stream()
                .filter(c -> "YES".equals(c.expectedConfirmation()))
                .map(c -> Awaiting.valueOf(c.state().awaiting()))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Awaiting.class)));

        assertThat(confirmed).containsAll(CONFIRM_GATES);
    }
}
