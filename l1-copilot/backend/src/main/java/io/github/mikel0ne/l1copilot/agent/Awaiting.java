package io.github.mikel0ne.l1copilot.agent;

public enum Awaiting {

    NOTHING,

    ORDER_CHOICE,

    CANCEL_CONFIRM,

    RETURN_CONFIRM,

    TICKET_CONFIRM,

    TICKET_INFO;

    public static Awaiting fromState(String raw) {
        if (raw == null || raw.isBlank()) {
            return NOTHING;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NOTHING;
        }
    }
}
