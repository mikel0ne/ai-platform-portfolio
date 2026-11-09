package io.github.mikel0ne.l1copilot.dto;

public record ReturnCardDto(Long id, String code, String description, String status, String createdAt) {
    public static String code(long orderId) {
        return String.format("RET-%04d", orderId);
    }
}
