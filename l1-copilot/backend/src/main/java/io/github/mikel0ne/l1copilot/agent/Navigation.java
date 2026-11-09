package io.github.mikel0ne.l1copilot.agent;

public enum Navigation {

    NONE,

    BACK_TO_LIST,

    BACK_TO_MENU,

    BACK;

    public static Navigation fromLlm(String raw) {
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
