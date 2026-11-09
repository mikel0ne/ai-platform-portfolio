package io.github.mikel0ne.l1copilot.dto.tools;

import java.io.Serializable;
import java.time.Instant;

public record TicketCreated(Long ticketId, String subject, String status, Instant createdAt)
        implements Serializable {
}
