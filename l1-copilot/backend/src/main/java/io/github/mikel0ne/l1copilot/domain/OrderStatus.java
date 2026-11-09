package io.github.mikel0ne.l1copilot.domain;

public enum OrderStatus {

    CREATED, PAID, SHIPPED, IN_DELIVERY, DELIVERED, CANCELLED, RETURN_REQUESTED;

    public boolean isCancellable() {
        return this == CREATED || this == PAID;
    }

    public boolean isReturnable() {
        return this == DELIVERED;
    }
}
