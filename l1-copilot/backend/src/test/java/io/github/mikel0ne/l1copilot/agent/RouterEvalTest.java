package io.github.mikel0ne.l1copilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier.RouterDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ROUTER_EVAL", matches = "true")
class RouterEvalTest {

    private static final double DEFAULT_MIN_ACCURACY = 0.7;

    @Autowired
    private IntentClassifier classifier;

    private final ObjectMapper mapper = new ObjectMapper();

    record GoldenCase(String message, String expectedIntent) {
    }

    @Test
    @DisplayName("The router classifies the golden set above the minimum threshold")
    void routerMeetsAccuracyThreshold() throws Exception {
        List<GoldenCase> cases = mapper.readValue(
                new ClassPathResource("golden-router-cases.json").getInputStream(),
                mapper.getTypeFactory().constructCollectionType(List.class, GoldenCase.class));

        int hits = 0;
        StringBuilder report = new StringBuilder("\n=== Router eval ===\n");
        for (GoldenCase c : cases) {
            RouterDecision decision = classifier.classify(
                    c.message(), "NONE", "NOTHING", "(ninguno)", "(ninguno)", "(sin historial previo)");
            Intent got = Intent.fromLlm(decision.intent());
            boolean ok = got == Intent.fromLlm(c.expectedIntent());
            if (ok) {
                hits++;
            }
            report.append(ok ? "  [ok] " : "  [XX] ")
                    .append(String.format("%-8s", c.expectedIntent()))
                    .append(" -> ").append(String.format("%-8s", got))
                    .append(" | ").append(c.message()).append('\n');
        }

        double accuracy = (double) hits / cases.size();
        report.append(String.format("Precisión: %d/%d = %.0f%%%n", hits, cases.size(), accuracy * 100));
        System.out.println(report);

        double min = Double.parseDouble(
                System.getenv().getOrDefault("ROUTER_EVAL_MIN", String.valueOf(DEFAULT_MIN_ACCURACY)));
        assertThat(accuracy)
                .as("Precisión del router sobre el golden-set (umbral %.0f%%)", min * 100)
                .isGreaterThanOrEqualTo(min);
    }
}
