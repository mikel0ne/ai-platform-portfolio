package io.github.mikel0ne.l1copilot.dto;

import io.github.mikel0ne.l1copilot.domain.Ticket;

public record TicketDetailDto(Long id, String code, String title, String message,
                              String status, Long orderId, String createdAt) {
    public static TicketDetailDto from(Ticket t) {
        return new TicketDetailDto(
                t.getId(),
                TicketCardDto.code(t.getId()),
                t.getSubject(),
                t.getDescription(),
                t.getStatus().name(),
                t.getOrderId(),
                t.getCreatedAt().toString());
    }
}
