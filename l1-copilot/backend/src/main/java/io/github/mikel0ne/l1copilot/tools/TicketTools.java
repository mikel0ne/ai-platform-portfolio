package io.github.mikel0ne.l1copilot.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.mikel0ne.l1copilot.domain.Ticket;
import io.github.mikel0ne.l1copilot.domain.TicketStatus;
import io.github.mikel0ne.l1copilot.domain.repository.OrderRepository;
import io.github.mikel0ne.l1copilot.domain.repository.TicketRepository;
import io.github.mikel0ne.l1copilot.dto.tools.TicketCreated;
import io.github.mikel0ne.l1copilot.dto.tools.TicketSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Herramienta de escalado: crea tickets para que un agente humano atienda
 * la incidencia. Misma política de seguridad que {@link OrderTools}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TicketTools {

    private final TicketRepository ticketRepository;
    private final OrderRepository orderRepository;

    @Tool("Lista las incidencias (tickets de soporte) abiertas por el usuario, de la más reciente a la más antigua")
    @Transactional(readOnly = true)
    public List<TicketSummary> listTickets(String userId) {
        List<TicketSummary> tickets = ticketRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(t -> new TicketSummary(t.getId(), t.getSubject(), t.getStatus().name(),
                        t.getOrderId(), t.getCreatedAt()))
                .toList();
        log.debug("Consulta de incidencias del usuario {}: {} ticket(s)", userId, tickets.size());
        return tickets;
    }

    @Tool("Crea un ticket de soporte para que un agente humano atienda la incidencia")
    @Transactional
    public TicketCreated createTicket(String userId,
                                      @P("ID del pedido afectado, o null si no aplica") Long orderId,
                                      @P("Resumen corto de la incidencia") String subject,
                                      @P("Descripción detallada dada por el usuario") String description) {
        if (orderId != null) {
            // Si referencia un pedido, debe ser del propio usuario
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
        log.info("Ticket {} creado para el usuario {} (pedido: {})", t.getId(), userId, orderId);
        return new TicketCreated(t.getId(), t.getSubject(), t.getStatus().name(), t.getCreatedAt());
    }
}
