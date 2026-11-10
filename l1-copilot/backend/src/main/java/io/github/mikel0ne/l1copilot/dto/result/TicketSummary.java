package io.github.mikel0ne.l1copilot.dto.result;

import java.io.Serializable;
import java.time.Instant;

public record TicketSummary(Long ticketId, String subject, String status, Long orderId, Instant createdAt)
        implements Serializable {
}
