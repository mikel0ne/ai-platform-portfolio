package io.github.mikel0ne.l1copilot.agent;

/**
 * Qué pregunta dejó pendiente el turno anterior. Es la pieza que implementa
 * el ciclo conversacional: el grafo termina el turno con una pregunta, el
 * checkpointer conserva este valor y el router del turno siguiente interpreta
 * el mensaje del usuario como respuesta a esa pregunta.
 */
public enum Awaiting {
    /** No hay pregunta pendiente. */
    NOTHING,
    /** Se ofrecieron varios pedidos y se espera que el usuario elija uno. */
    ORDER_CHOICE,
    /** Se pidió confirmación explícita (sí/no) antes de cancelar. */
    CANCEL_CONFIRM,
    /** Se propuso abrir una incidencia y se espera que el usuario confirme que la
     *  quiere y describa con detalle qué ha ocurrido (gate previo a crear el ticket). */
    TICKET_CONFIRM,
    /** Se pidió ampliar la descripción del problema para abrir un ticket. */
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
