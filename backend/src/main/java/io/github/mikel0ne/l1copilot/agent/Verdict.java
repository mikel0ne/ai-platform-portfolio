package io.github.mikel0ne.l1copilot.agent;

/**
 * Veredicto del nodo verify sobre la decisión del router. Tres niveles en vez de
 * un sí/no: el punto medio ({@link #DOUBT}) convierte un posible fallo en casi
 * acierto, porque permite graduar la respuesta según el riesgo de la acción.
 * <ul>
 *   <li>{@link #SURE} — la acción es claramente lo que el usuario pide: se ejecuta.</li>
 *   <li>{@link #DOUBT} — plausible pero ambiguo: en lecturas se sigue adelante con
 *   tono blando (ofrecer en vez de afirmar); en escrituras se pregunta antes de
 *   actuar, ofreciendo primero la acción sospechada.</li>
 *   <li>{@link #REJECT} — claramente NO es eso ("ni de coña"): freno. En lecturas se
 *   reorienta; en escrituras se pregunta liderando con las alternativas.</li>
 * </ul>
 */
public enum Verdict {
    SURE, DOUBT, REJECT;

    /**
     * Parseo tolerante de la salida del LLM. Ante cualquier cosa no reconocida o
     * vacía se devuelve {@link #DOUBT}: el valor por defecto prudente —ni ejecuta a
     * ciegas ni frena en seco—, que degrada con gracia si el modelo cutre desbarra.
     */
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
