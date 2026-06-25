package io.github.mikel0ne.l1copilot.agent;

/**
 * Registro con el que el asistente se dirige al usuario. Unifica el tono de TODOS
 * los mensajes (los que genera el LLM y los pocos hardcodeados):
 * <ul>
 *   <li>{@link #TU} — tuteo: educado, simpático y profesional;</li>
 *   <li>{@link #USTED} — de usted: educado, amable y profesional.</li>
 * </ul>
 * Se decide por la edad del usuario (umbral configurable): a partir del umbral,
 * trato de usted.
 */
public enum Treatment {
    TU, USTED;

    /** Tratamiento según la edad: de usted a partir del umbral, tuteo por debajo. */
    public static Treatment forAge(Integer age, int formalThreshold) {
        return age != null && age >= formalThreshold ? USTED : TU;
    }

    /** Parseo tolerante desde el estado del grafo; por defecto tuteo. */
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
