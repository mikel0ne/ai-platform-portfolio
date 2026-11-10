package io.github.mikel0ne.l1copilot.agent;

public enum Confirmation {

    YES, NO, NONE;

    public static Confirmation fromState(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
