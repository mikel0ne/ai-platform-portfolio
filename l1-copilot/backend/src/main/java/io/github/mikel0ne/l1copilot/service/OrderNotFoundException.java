package io.github.mikel0ne.l1copilot.service;

public class OrderNotFoundException extends RuntimeException {

    private final Long orderId;

    public OrderNotFoundException(Long orderId) {
        super("Order " + orderId + " not found for this user");
        this.orderId = orderId;
    }

    public Long getOrderId() {
        return orderId;
    }
}
