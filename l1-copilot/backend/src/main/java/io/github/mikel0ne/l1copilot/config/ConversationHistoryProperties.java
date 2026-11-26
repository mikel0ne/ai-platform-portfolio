package io.github.mikel0ne.l1copilot.config;

import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.Turn;
import io.github.mikel0ne.l1copilot.agent.llm.PromptData;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "copilot.history")
public record ConversationHistoryProperties(Window classifier, Window responder) {
    private static final int DEFAULT_SIZE = 6;

    public ConversationHistoryProperties {
        if (classifier == null) {
            classifier = new Window(true, DEFAULT_SIZE);
        }
        if (responder == null) {
            responder = new Window(true, DEFAULT_SIZE);
        }
    }

    public record Window(Boolean enabled, Integer size) {
        // The window skips the current message, and compaction can run right when it is appended.
        public static final int MAX_SIZE = SupportState.RETAINED_TURNS - 1;
        private static final String NONE = "(sin historial previo)";

        public Window {
            if (enabled == null) {
                enabled = true;
            }
            if (size == null || size < 0) {
                size = DEFAULT_SIZE;
            }
            if (size > MAX_SIZE) {
                throw new IllegalArgumentException("copilot.history window size " + size
                        + " exceeds " + MAX_SIZE + ": the stored history keeps only the last "
                        + SupportState.RETAINED_TURNS + " turns after compaction");
            }
        }

        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }

        public String render(List<Turn> history) {
            if (!isEnabled() || history == null || history.size() <= 1) {
                return NONE;
            }

            List<Turn> previous = history.subList(0, history.size() - 1);
            int from = Math.max(0, previous.size() - size);
            return PromptData.turns(previous.subList(from, previous.size()));
        }
    }
}
