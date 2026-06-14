package io.github.mikel0ne.l1copilot.dto.tools;

import java.io.Serializable;

/**
 * Resultado de comprobar si un pedido puede cancelarse (sin ejecutar nada).
 * El flag {@code cancellable} alimenta la arista condicional del grafo:
 * nada de parsear strings para decidir el flujo.
 */
public record CancellationCheck(Long orderId, boolean cancellable, String status, String description)
        implements Serializable {
}
