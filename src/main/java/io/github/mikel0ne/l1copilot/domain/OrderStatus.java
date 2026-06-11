package io.github.mikel0ne.l1copilot.domain;

/**
 * Ciclo de vida del pedido:
 * <pre>
 * CREATED ──&gt; PAID ──&gt; SHIPPED ──&gt; IN_DELIVERY ──&gt; DELIVERED
 *    │          │
 *    └──────────┴────&gt; CANCELLED
 * </pre>
 */
public enum OrderStatus {
    CREATED, PAID, SHIPPED, IN_DELIVERY, DELIVERED, CANCELLED;

    /**
     * Regla de negocio: solo se puede cancelar antes del envío.
     * Vive en código Java, no en un prompt.
     */
    public boolean isCancellable() {
        return this == CREATED || this == PAID;
    }
}
