package io.github.mikel0ne.l1copilot.dto.tools;

import java.io.Serializable;
import java.time.Instant;

/**
 * Resumen de un ticket de soporte del usuario, para la consulta de incidencias.
 * Serializable: viaja dentro del estado del grafo (checkpointer).
 */
public record TicketSummary(Long ticketId, String subject, String status, Long orderId, Instant createdAt)
        implements Serializable {
}

