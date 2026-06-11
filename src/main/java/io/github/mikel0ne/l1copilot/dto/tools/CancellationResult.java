package io.github.mikel0ne.l1copilot.dto.tools;

import java.io.Serializable;

/**
 * Resultado de una comprobación o ejecución de cancelación.
 *
 * @param cancelled {@code true} solo si la cancelación se EJECUTÓ
 * @param reason    motivo legible (cancelable, rechazo y su causa, o confirmación)
 */
public record CancellationResult(Long orderId, boolean cancelled, String reason)
        implements Serializable {
}
