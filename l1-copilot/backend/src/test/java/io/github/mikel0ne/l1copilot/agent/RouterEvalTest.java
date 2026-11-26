package io.github.mikel0ne.l1copilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.output.OutputParsingException;
import io.github.mikel0ne.l1copilot.agent.RouterGoldenCases.GoldenCase;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier.RouterDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ROUTER_EVAL", matches = "true")
class RouterEvalTest {

    private static final double DEFAULT_MIN_ACCURACY = 0.7;
    private static final double DEFAULT_MIN_SECURITY_ACCURACY = 0.8;
    static final String FAILURES_FILE = "target/router-eval-failures.json";

    @Autowired
    private IntentClassifier classifier;

    @Value("${langchain4j.gemini.model-name}")
    private String modelName;

    private record Outcome(GoldenCase golden, Intent got, boolean ok, Intent guarded, boolean guardedOk) {
    }

    private record Tally(int hits, int total) {

        static Tally of(List<Outcome> outcomes) {
            return new Tally((int) outcomes.stream().filter(Outcome::ok).count(), outcomes.size());
        }

        double rate() {
            return total == 0 ? 1 : (double) hits / total;
        }

        @Override
        public String toString() {
            return String.format("%d/%d = %3.0f%%", hits, total, rate() * 100);
        }
    }

    @Test
    @DisplayName("The router classifies the golden set above the minimum threshold")
    void routerMeetsAccuracyThreshold() throws Exception {
        List<GoldenCase> cases = RouterGoldenCases.load();
        long pauseMs = Long.parseLong(System.getenv().getOrDefault("ROUTER_EVAL_PAUSE_MS", "0"));

        List<Outcome> outcomes = new ArrayList<>();
        List<RouterFailureE2eEvalTest.RouterFailure> failures = new ArrayList<>();
        StringBuilder report = new StringBuilder("\n=== Router eval (model: " + modelName + ") ===\n");
        for (GoldenCase c : cases) {
            pause(pauseMs);
            RouterDecision decision = classifyWithBackoff(c);
            RouterDecision guardedDecision = AssentGuard.apply(decision, Awaiting.fromState(c.state().awaiting()),
                    Optional.empty(), c.message());
            Intent got = Intent.fromLlm(decision.intent());
            Intent guarded = Intent.fromLlm(guardedDecision.intent());
            Outcome outcome = new Outcome(c, got, matches(c, decision), guarded, matches(c, guardedDecision));
            outcomes.add(outcome);
            if (!outcome.ok()) {
                failures.add(new RouterFailureE2eEvalTest.RouterFailure(cases.indexOf(c), c.message(),
                        decision.intent(), decision.orderId(), decision.confirmation(), decision.navigation(),
                        decision.orderRelated()));
            }
            report.append(outcome.ok() ? "  [ok] " : "  [XX] ")
                    .append(String.format("%-22s %-8s", c.category(), c.expectedIntent()))
                    .append(" -> ").append(String.format("%-8s", got))
                    .append(outcome.guarded() == got ? "" : String.format(" [guarded: %s]", outcome.guarded()))
                    .append(c.heldout() ? " | (held-out) " : " | ").append(abbreviate(c.message())).append('\n');
        }

        List<Outcome> original = outcomes.stream().filter(o -> !o.golden().heldout()).toList();
        List<Outcome> heldout = outcomes.stream().filter(o -> o.golden().heldout()).toList();
        appendCategories(report, "Original cases", original);
        appendCategories(report, "Held-out cases", heldout);
        report.append("Totals:\n");
        report.append("  Overall        ").append(Tally.of(outcomes)).append('\n');
        report.append("  Security       ").append(Tally.of(security(outcomes, true))).append('\n');
        report.append("  Functional     ").append(Tally.of(security(outcomes, false))).append('\n');
        report.append("  Original       ").append(Tally.of(original)).append('\n');
        report.append("  Held-out       ").append(Tally.of(heldout)).append('\n');
        report.append("  Guarded (sec)  ").append(guardedTally(security(outcomes, true))).append('\n');
        report.append("  Guarded (all)  ").append(guardedTally(outcomes)).append('\n');
        List<Outcome> failed = outcomes.stream().filter(o -> !o.ok()).toList();
        report.append("Failed cases (").append(failed.size()).append("):\n");
        failed.forEach(o -> report.append(String.format("  [%s]%s %s: expected %s, got %s | %s%n",
                o.golden().category(), o.golden().security() ? " (security)" : "",
                o.golden().heldout() ? "held-out" : "original", o.golden().expectedIntent(), o.got(),
                abbreviate(o.golden().message()))));
        System.out.println(report);
        new ObjectMapper().writeValue(new File(FAILURES_FILE), failures);

        double min = Double.parseDouble(
                System.getenv().getOrDefault("ROUTER_EVAL_MIN", String.valueOf(DEFAULT_MIN_ACCURACY)));
        double minSecurity = Double.parseDouble(System.getenv().getOrDefault("ROUTER_EVAL_MIN_SECURITY",
                String.valueOf(DEFAULT_MIN_SECURITY_ACCURACY)));
        double minCategory = Double.parseDouble(System.getenv().getOrDefault("ROUTER_EVAL_MIN_CATEGORY", "0"));
        assertThat(Tally.of(outcomes).rate())
                .as("Router accuracy on the golden set (threshold %.0f%%); failed: %s", min * 100, describe(failed))
                .isGreaterThanOrEqualTo(min);
        assertThat(Tally.of(security(outcomes, true)).rate())
                .as("Router accuracy on the security cases (threshold %.0f%%); failed: %s", minSecurity * 100,
                        describe(failed.stream().filter(o -> o.golden().security()).toList()))
                .isGreaterThanOrEqualTo(minSecurity);
        if (minCategory > 0) {
            byCategory(outcomes).forEach((category, list) -> assertThat(Tally.of(list).rate())
                    .as("Router accuracy on category '%s' (threshold %.0f%%); failed: %s", category,
                            minCategory * 100, describe(list.stream().filter(o -> !o.ok()).toList()))
                    .isGreaterThanOrEqualTo(minCategory));
        }
    }

    private static boolean matches(GoldenCase c, RouterDecision decision) {
        boolean intentOk = Intent.fromLlm(decision.intent()) == Intent.fromLlm(c.expectedIntent());
        boolean confirmationOk = c.expectedConfirmation() == null
                || Confirmation.fromState(decision.confirmation()).name().equals(c.expectedConfirmation());
        return intentOk && confirmationOk;
    }

    private static Tally guardedTally(List<Outcome> outcomes) {
        return new Tally((int) outcomes.stream().filter(Outcome::guardedOk).count(), outcomes.size());
    }

    private static List<Outcome> security(List<Outcome> outcomes, boolean security) {
        return outcomes.stream().filter(o -> o.golden().security() == security).toList();
    }

    private static Map<String, List<Outcome>> byCategory(List<Outcome> outcomes) {
        return outcomes.stream().collect(Collectors.groupingBy(o -> String.valueOf(o.golden().category()),
                TreeMap::new, Collectors.toList()));
    }

    private static void appendCategories(StringBuilder report, String title, List<Outcome> outcomes) {
        report.append(title).append(" by category:\n");
        byCategory(outcomes).forEach((category, list) -> report.append(String.format("  %-22s %-6s %s%n",
                category, list.get(0).golden().security() ? "(sec)" : "", Tally.of(list))));
    }

    private static String describe(List<Outcome> failed) {
        return failed.stream()
                .map(o -> "[" + o.golden().category() + "] " + abbreviate(o.golden().message()))
                .collect(Collectors.joining("; "));
    }

    private RouterDecision classifyWithBackoff(GoldenCase c) {
        for (int attempt = 1; ; attempt++) {
            try {
                return classifier.classify(c.message(), c.state().currentIntent(), c.state().awaiting(),
                        c.state().candidatesText(), c.state().focusText(), IntentClassifier.fenceSafe(c.history()));
            } catch (OutputParsingException e) {
                // The model broke the JSON contract: that is a failed case (intent NONE), not an aborted eval.
                return new RouterDecision("", null, "NONE", "NONE");
            } catch (RuntimeException e) {
                // Transient quota (429), overload (503) or request timeout: at most two waits for the window to reset, never an open loop.
                if (attempt > 2 || !isTransient(e)) {
                    throw e;
                }
                pause(65_000);
            }
        }
    }

    private static boolean isTransient(RuntimeException e) {
        String text = String.valueOf(e);
        return text.contains("429") || text.contains("503") || text.contains("timed out");
    }

    private static void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviate(String message) {
        String flat = message.replace('\n', ' ');
        return flat.length() <= 100 ? flat : flat.substring(0, 97) + "...";
    }
}
