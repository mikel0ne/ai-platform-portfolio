package io.github.mikel0ne.l1copilot.tools;

/**
 * El pedido no existe o no pertenece al usuario autenticado.
 * Deliberadamente no distingue ambos casos: no se revela la existencia
 * de pedidos ajenos.
 */
public class OrderNotFoundException extends RuntimeException {

    private final Long orderId;

    public OrderNotFoundException(Long orderId) {
        super("Pedido " + orderId + " no encontrado para este usuario");
        this.orderId = orderId;
    }

    public Long getOrderId() {
        return orderId;
    }
}
