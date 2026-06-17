package io.github.mikel0ne.l1copilot.agent;

import java.util.Optional;

/**
 * Convención de "comandos" que la interfaz envía al pulsar un botón, en lugar de
 * texto libre del usuario. Permiten que acciones críticas (p. ej. confirmar una
 * cancelación) lleguen al backend como una señal inequívoca y se resuelvan de
 * forma determinista en el router, sin pasar por el LLM.
 * <p>
 * Son la cara de entrada de lo que {@code ConversationService} genera como
 * {@code confirmPayload}/{@code cancelPayload} en el DTO de confirmación: misma
 * convención a ambos lados.
 */
public final class UiAction {

    /** Confirmar la cancelación del pedido indicado: {@code action:confirm-cancel:<id>}. */
    public static final String CONFIRM_CANCEL = "action:confirm-cancel:";
    /** Descartar la cancelación del pedido indicado: {@code action:abort-cancel:<id>}. */
    public static final String ABORT_CANCEL = "action:abort-cancel:";

    private UiAction() {
    }

    /** Payload que la UI debe enviar para confirmar la cancelación de un pedido. */
    public static String confirmCancel(long orderId) {
        return CONFIRM_CANCEL + orderId;
    }

    /** Payload que la UI debe enviar para descartar la cancelación de un pedido. */
    public static String abortCancel(long orderId) {
        return ABORT_CANCEL + orderId;
    }

    /** El id de pedido de un comando con el prefijo dado, si el mensaje lo es. */
    public static Optional<Long> orderIdOf(String message, String prefix) {
        if (message == null) {
            return Optional.empty();
        }
        String trimmed = message.trim();
        if (!trimmed.startsWith(prefix)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(trimmed.substring(prefix.length()).trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Id de pedido si el mensaje es exactamente una referencia "#&lt;id&gt;" (clic en tarjeta). */
    public static Optional<Long> bareOrderId(String message) {
        if (message == null) {
            return Optional.empty();
        }
        String trimmed = message.trim();
        if (!trimmed.matches("#\\d+")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(trimmed.substring(1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
