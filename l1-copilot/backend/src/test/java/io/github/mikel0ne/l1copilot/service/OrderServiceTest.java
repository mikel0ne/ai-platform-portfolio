package io.github.mikel0ne.l1copilot.service;

import io.github.mikel0ne.l1copilot.domain.OrderStatus;
import io.github.mikel0ne.l1copilot.domain.TicketStatus;
import io.github.mikel0ne.l1copilot.domain.repository.OrderRepository;
import io.github.mikel0ne.l1copilot.domain.repository.TicketRepository;
import io.github.mikel0ne.l1copilot.dto.result.CancellationCheck;
import io.github.mikel0ne.l1copilot.dto.result.CancellationResult;
import io.github.mikel0ne.l1copilot.dto.result.OrderSummary;
import io.github.mikel0ne.l1copilot.dto.result.ShipmentStatus;
import io.github.mikel0ne.l1copilot.dto.result.TicketCreated;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@Import({OrderService.class, TicketService.class})
class OrderServiceTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Test
    @DisplayName("Returns the user's orders sorted by date, newest first")
    void recentOrdersAreOrderedByDateDesc() {
        List<OrderSummary> orders = orderService.getRecentOrders("u-001");

        assertThat(orders).extracting(OrderSummary::id).containsExactly(456L, 123L, 789L);
    }

    @Test
    @DisplayName("A user without orders gets an empty list, not an error")
    void unknownUserGetsEmptyList() {
        assertThat(orderService.getRecentOrders("u-999")).isEmpty();
    }

    @Test
    @DisplayName("Reports status, estimated date and carrier of the user's own order")
    void trackOwnShipment() {
        ShipmentStatus s = orderService.trackShipment(123L, "u-001");

        assertThat(s.status()).isEqualTo("IN_DELIVERY");
        assertThat(s.carrier()).isEqualTo("SEUR");
        assertThat(s.estimatedDelivery()).isNotNull();
    }

    @Test
    @DisplayName("SECURITY: another user's order does not exist for this user")
    void cannotTrackForeignOrder() {
        assertThatThrownBy(() -> orderService.trackShipment(201L, "u-001"))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("A CREATED order is cancellable; a SHIPPED one is not")
    void cancellabilityFollowsBusinessRules() {
        CancellationCheck created = orderService.checkCancellable(200L, "u-002");
        CancellationCheck shipped = orderService.checkCancellable(201L, "u-002");

        assertThat(created.cancellable()).isTrue();
        assertThat(shipped.cancellable()).isFalse();
        assertThat(shipped.description()).contains("SHIPPED");
    }

    @Test
    @DisplayName("Cancelling a cancellable order leaves it CANCELLED in the database")
    void cancelExecutesAndPersists() {
        CancellationResult result = orderService.cancelOrder(200L, "u-002");

        assertThat(result.cancelled()).isTrue();
        assertThat(orderRepository.findById(200L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("Cancelling a shipped order is rejected BY CODE, with a reason")
    void cancelShippedIsRejected() {
        CancellationResult result = orderService.cancelOrder(201L, "u-002");

        assertThat(result.cancelled()).isFalse();
        assertThat(result.reason()).contains("SHIPPED");
        assertThat(orderRepository.findById(201L).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("Creates an OPEN ticket linked to the user's own order")
    void createTicketForOwnOrder() {
        TicketCreated t = ticketService.createTicket("u-001", 123L,
                "Pantalla rota", "El portátil llegó con la pantalla rota");

        assertThat(t.status()).isEqualTo(TicketStatus.OPEN.name());
        assertThat(ticketRepository.findByUserIdOrderByCreatedAtDesc("u-001"))
                .anyMatch(ticket -> ticket.getId().equals(t.ticketId()));
    }

    @Test
    @DisplayName("SECURITY: a ticket cannot be opened on someone else's order")
    void cannotCreateTicketForForeignOrder() {
        assertThatThrownBy(() -> ticketService.createTicket("u-001", 201L, "x", "y"))
                .isInstanceOf(OrderNotFoundException.class);
    }
}
