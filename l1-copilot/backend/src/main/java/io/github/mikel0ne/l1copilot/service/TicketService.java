package io.github.mikel0ne.l1copilot.service;

import io.github.mikel0ne.l1copilot.domain.Ticket;
import io.github.mikel0ne.l1copilot.domain.TicketStatus;
import io.github.mikel0ne.l1copilot.domain.repository.OrderRepository;
import io.github.mikel0ne.l1copilot.domain.repository.TicketRepository;
import io.github.mikel0ne.l1copilot.dto.result.TicketCreated;
import io.github.mikel0ne.l1copilot.dto.result.TicketSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketService {

    private final TicketRepository ticketRepository;
    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public List<TicketSummary> listTickets(String userId) {
        List<TicketSummary> tickets = ticketRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(t -> new TicketSummary(t.getId(), t.getSubject(), t.getStatus().name(),
                        t.getOrderId(), t.getCreatedAt()))
                .toList();
        log.debug("Ticket lookup for user {}: {} ticket(s)", userId, tickets.size());
        return tickets;
    }

    @Transactional
    public TicketCreated createTicket(String userId,
                                      Long orderId,
                                      String subject,
                                      String description) {
        if (orderId != null) {
            orderRepository.findByIdAndUserId(orderId, userId)
                    .orElseThrow(() -> new OrderNotFoundException(orderId));
        }
        Ticket t = new Ticket();
        t.setUserId(userId);
        t.setOrderId(orderId);
        t.setSubject(subject);
        t.setDescription(description);
        t.setStatus(TicketStatus.OPEN);
        t.setCreatedAt(Instant.now());
        t = ticketRepository.save(t);
        log.info("Ticket {} created for user {} (order: {})", t.getId(), userId, orderId);
        return new TicketCreated(t.getId(), t.getSubject(), t.getStatus().name(), t.getCreatedAt());
    }
}
