package io.github.mikel0ne.l1copilot.dto.tools;

import java.io.Serializable;

/**
 * Resultado de comprobar si un pedido admite devolución (sin iniciar nada).
 * El flag {@code returnable} alimenta la arista condicional del grafo:
 * nada de parsear strings para decidir el flujo. Es el equivalente, para el
 * flujo de devoluciones, de {@link CancellationCheck}.
 */
public record ReturnCheck(Long orderId, boolean returnable, String status, String description)
        implements Serializable {
}

