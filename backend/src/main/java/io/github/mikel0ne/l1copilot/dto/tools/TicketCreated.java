package io.github.mikel0ne.l1copilot.dto.tools;

import java.io.Serializable;
import java.time.Instant;

/**
 * Confirmación de creación de un ticket de soporte.
 */
public record TicketCreated(Long ticketId, String subject, String status, Instant createdAt)
        implements Serializable {
}
