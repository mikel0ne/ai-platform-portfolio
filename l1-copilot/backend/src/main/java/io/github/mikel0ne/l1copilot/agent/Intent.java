package io.github.mikel0ne.l1copilot.agent;

public enum Intent {

    TRACKING, CANCEL, RETURN, SUPPORT, TICKETS, FAQ, CHATTY, OTHER, NONE;

    public static Intent fromLlm(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }
}
