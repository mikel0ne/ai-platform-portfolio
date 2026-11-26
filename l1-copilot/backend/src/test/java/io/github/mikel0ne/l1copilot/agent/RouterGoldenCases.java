package io.github.mikel0ne.l1copilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

final class RouterGoldenCases {

    static final String RESOURCE = "golden-router-cases.json";

    static final Set<String> FUNCTIONAL_CATEGORIES = Set.of(
            "baseline", "meta", "complaint", "chatty", "typo", "language", "multi-intent", "intent-boundary",
            "multilingual", "false-positive", "state-confirm", "short-with-focus");

    static final Set<String> SECURITY_CATEGORIES = Set.of(
            "jailbreak", "prompt-leak", "injection", "camouflaged-injection", "privilege", "malformed",
            "history-injection", "multilingual-injection", "encoded-injection", "roleplay", "tool-data-injection",
            "agent-turn-injection");

    static final Set<String> HISTORY_REQUIRED_CATEGORIES = Set.of(
            "history-injection", "roleplay", "agent-turn-injection");

    static final Set<String> HISTORY_ALLOWED_CATEGORIES = Set.of(
            "history-injection", "roleplay", "agent-turn-injection", "tool-data-injection", "state-confirm",
            "short-with-focus");

    static final Set<String> FORCED_INTENT_CATEGORIES = Set.of(
            "jailbreak", "injection", "multilingual-injection", "encoded-injection", "roleplay", "tool-data-injection",
            "agent-turn-injection");

    private RouterGoldenCases() {
    }

    static final String NO_HISTORY = "(sin historial previo)";
    static final String NO_VALUE = "(ninguno)";

    record State(String awaiting, String currentIntent, List<String> candidates, Long focus) {

        static final State DEFAULT = new State(null, null, null, null);

        State {
            awaiting = awaiting == null || awaiting.isBlank() ? Awaiting.NOTHING.name() : awaiting;
            currentIntent = currentIntent == null || currentIntent.isBlank() ? Intent.NONE.name() : currentIntent;
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        String candidatesText() {
            return candidates.isEmpty() ? NO_VALUE : String.join("; ", candidates);
        }

        String focusText() {
            return focus == null ? NO_VALUE : "#" + focus;
        }

        boolean isDefault() {
            return equals(DEFAULT);
        }
    }

    record GoldenCase(String message, String history, String expectedIntent, String category, String rationale,
                      boolean heldout, State state, String expectedConfirmation) {

        GoldenCase {
            if (history == null || history.isBlank()) {
                history = NO_HISTORY;
            }
            if (state == null) {
                state = State.DEFAULT;
            }
        }

        boolean security() {
            return SECURITY_CATEGORIES.contains(category);
        }
    }

    static List<GoldenCase> load() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return mapper.readValue(in, mapper.getTypeFactory().constructCollectionType(List.class, GoldenCase.class));
        }
    }
}
