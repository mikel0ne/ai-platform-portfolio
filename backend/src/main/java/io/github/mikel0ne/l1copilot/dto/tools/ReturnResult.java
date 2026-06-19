package io.github.mikel0ne.l1copilot.dto.tools;

import java.io.Serializable;

/**
 * Resultado de iniciar una devolución.
 *
 * @param returned  {@code true} solo si la devolución se INICIÓ realmente
 * @param reference referencia legible de la devolución (p. ej. {@code RET-0789}),
 *                  o {@code null} si no llegó a iniciarse
 * @param reason    motivo legible (devolución iniciada o causa del rechazo)
 */
public record ReturnResult(Long orderId, boolean returned, String reference, String reason)
        implements Serializable {
}

