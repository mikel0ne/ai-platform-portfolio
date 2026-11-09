package io.github.mikel0ne.l1copilot.config;

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
        private static final String NONE = "(sin historial previo)";

        public Window {
            if (enabled == null) {
                enabled = true;
            }
            if (size == null || size < 0) {
                size = DEFAULT_SIZE;
            }
        }

        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }

        public String render(List<String> history) {
            if (!isEnabled() || history == null || history.size() <= 1) {
                return NONE;
            }

            List<String> previous = history.subList(0, history.size() - 1);
            int from = Math.max(0, previous.size() - size);
            return String.join("\n", previous.subList(from, previous.size()));
        }
    }
}
