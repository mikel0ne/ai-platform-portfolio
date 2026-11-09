package io.github.mikel0ne.l1copilot.agent;

public enum Treatment {

    TU, USTED;

    public static Treatment forAge(Integer age, int formalThreshold) {
        return age != null && age >= formalThreshold ? USTED : TU;
    }

    public static Treatment fromState(String raw) {
        if (raw == null || raw.isBlank()) {
            return TU;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return TU;
        }
    }
}
