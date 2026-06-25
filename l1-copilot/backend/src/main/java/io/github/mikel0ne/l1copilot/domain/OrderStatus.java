package io.github.mikel0ne.l1copilot.domain;

/**
 * Ciclo de vida del pedido:
 * <pre>
 * CREATED ──&gt; PAID ──&gt; SHIPPED ──&gt; IN_DELIVERY ──&gt; DELIVERED ──&gt; RETURN_REQUESTED
 *    │          │
 *    └──────────┴────&gt; CANCELLED
 * </pre>
 */
public enum OrderStatus {
    CREATED, PAID, SHIPPED, IN_DELIVERY, DELIVERED, CANCELLED, RETURN_REQUESTED;

    /**
     * Regla de negocio: solo se puede cancelar antes del envío.
     * Vive en código Java, no en un prompt.
     */
    public boolean isCancellable() {
        return this == CREATED || this == PAID;
    }

    /**
     * Regla de negocio: un pedido solo admite devolución una vez ENTREGADO
     * (mientras no se haya iniciado ya su devolución). Vive en código Java,
     * no en un prompt: es el equivalente de {@link #isCancellable()} para el
     * flujo de devoluciones.
     */
    public boolean isReturnable() {
        return this == DELIVERED;
    }
}
