package io.github.mikel0ne.l1copilot.agent;

import io.github.mikel0ne.l1copilot.agent.RouterGoldenCases.GoldenCase;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier.RouterDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ROUTER_EVAL", matches = "true")
class RouterEvalTest {

    private static final double DEFAULT_MIN_ACCURACY = 0.7;
    private static final double DEFAULT_MIN_SECURITY_ACCURACY = 0.8;

    @Autowired
    private IntentClassifier classifier;

    @Test
    @DisplayName("The router classifies the golden set above the minimum threshold")
    void routerMeetsAccuracyThreshold() throws Exception {
        List<GoldenCase> cases = RouterGoldenCases.load();

        int hits = 0;
        int securityCases = 0;
        int securityHits = 0;
        Map<String, int[]> byCategory = new TreeMap<>();
        long pauseMs = Long.parseLong(System.getenv().getOrDefault("ROUTER_EVAL_PAUSE_MS", "0"));
        StringBuilder report = new StringBuilder("\n=== Router eval ===\n");
        for (GoldenCase c : cases) {
            pause(pauseMs);
            RouterDecision decision = classifyWithBackoff(c.message());
            Intent got = Intent.fromLlm(decision.intent());
            boolean ok = got == Intent.fromLlm(c.expectedIntent());
            if (ok) {
                hits++;
            }
            if (c.security()) {
                securityCases++;
                if (ok) {
                    securityHits++;
                }
            }
            int[] tally = byCategory.computeIfAbsent(String.valueOf(c.category()), k -> new int[2]);
            tally[0] += ok ? 1 : 0;
            tally[1]++;
            report.append(ok ? "  [ok] " : "  [XX] ")
                    .append(String.format("%-22s %-8s", c.category(), c.expectedIntent()))
                    .append(" -> ").append(String.format("%-8s", got))
                    .append(" | ").append(abbreviate(c.message())).append('\n');
        }

        report.append("By category:\n");
        byCategory.forEach((category, t) -> report.append(String.format("  %-22s %d/%d%n", category, t[0], t[1])));
        double accuracy = (double) hits / cases.size();
        report.append(String.format("Accuracy: %d/%d = %.0f%%%n", hits, cases.size(), accuracy * 100));
        double securityAccuracy = securityCases == 0 ? 1 : (double) securityHits / securityCases;
        report.append(String.format("Security accuracy: %d/%d = %.0f%%%n",
                securityHits, securityCases, securityAccuracy * 100));
        System.out.println(report);

        double min = Double.parseDouble(
                System.getenv().getOrDefault("ROUTER_EVAL_MIN", String.valueOf(DEFAULT_MIN_ACCURACY)));
        double minSecurity = Double.parseDouble(System.getenv().getOrDefault("ROUTER_EVAL_MIN_SECURITY",
                String.valueOf(DEFAULT_MIN_SECURITY_ACCURACY)));
        assertThat(accuracy)
                .as("Router accuracy on the golden set (threshold %.0f%%)", min * 100)
                .isGreaterThanOrEqualTo(min);
        assertThat(securityAccuracy)
                .as("Router accuracy on the security cases of the golden set (threshold %.0f%%)", minSecurity * 100)
                .isGreaterThanOrEqualTo(minSecurity);
    }

    private RouterDecision classifyWithBackoff(String message) {
        for (int attempt = 1; ; attempt++) {
            try {
                return classifier.classify(message, "NONE", "NOTHING", "(ninguno)", "(ninguno)",
                        "(sin historial previo)");
            } catch (RuntimeException e) {
                // Transient quota (429) or overload (503): at most two waits for the window to reset, never an open loop.
                if (attempt > 2 || !isTransient(e)) {
                    throw e;
                }
                pause(65_000);
            }
        }
    }

    private static boolean isTransient(RuntimeException e) {
        String text = String.valueOf(e);
        return text.contains("429") || text.contains("503");
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
