package io.github.mikel0ne.l1copilot.dto;

import io.github.mikel0ne.l1copilot.domain.Ticket;

/**
 * Detalle completo de un ticket de soporte para la página de detalle
 * ({@code /tickets/{id}}).
 *
 * @param id        identificador interno del ticket
 * @param code      código legible del ticket (p. ej. {@code SUP-0005})
 * @param title     asunto/título del ticket
 * @param message   descripción detallada de la incidencia
 * @param status    estado del ticket ({@code OPEN}, {@code IN_PROGRESS}, {@code CLOSED})
 * @param orderId   pedido asociado a la incidencia, o {@code null} si no aplica
 * @param createdAt fecha de creación en formato ISO-8601
 */
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
