package io.github.mikel0ne.l1copilot.dto;

import io.github.mikel0ne.l1copilot.domain.OrderStatus;

import java.io.Serializable;
import java.time.Instant;

public record ReturnCardDto(Long id, String code, String description, String status, String createdAt)
        implements Serializable {

    public static ReturnCardDto requested(long orderId, String description) {
        return new ReturnCardDto(orderId, code(orderId), description, OrderStatus.RETURN_REQUESTED.name(),
                Instant.now().toString());
    }

    public static String code(long orderId) {
        return String.format("RET-%04d", orderId);
    }
}
