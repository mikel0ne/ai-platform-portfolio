package io.github.mikel0ne.l1copilot.service;

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
