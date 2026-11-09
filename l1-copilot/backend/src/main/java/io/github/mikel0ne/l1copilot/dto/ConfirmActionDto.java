package io.github.mikel0ne.l1copilot.dto;

public record ConfirmActionDto(Long orderId, String title, String warning,
                               String confirmLabel, String confirmPayload,
                               String cancelLabel, String cancelPayload) {
}
