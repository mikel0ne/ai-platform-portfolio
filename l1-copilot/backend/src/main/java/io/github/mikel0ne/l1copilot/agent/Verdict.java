package io.github.mikel0ne.l1copilot.agent;

public enum Verdict {

    SURE, DOUBT, REJECT;

    public static Verdict fromLlm(String raw) {
        if (raw == null || raw.isBlank()) {
            return DOUBT;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DOUBT;
        }
    }
}
