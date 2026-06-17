package io.github.mikel0ne.l1copilot.agent;

/**
 * Intenciones que el nodo router puede detectar. Las decisiones de flujo
 * sobre ellas son código Java (aristas condicionales), no prompts.
 */
public enum Intent {
    TRACKING, CANCEL, SUPPORT, FAQ, CHATTY, OTHER, NONE;

    /** Parseo tolerante de la salida del LLM: cualquier cosa no reconocida es OTHER. */
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
