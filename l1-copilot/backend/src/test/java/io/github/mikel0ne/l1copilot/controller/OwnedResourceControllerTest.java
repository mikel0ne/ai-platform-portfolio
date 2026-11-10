package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.domain.Order;
import io.github.mikel0ne.l1copilot.domain.OrderStatus;
import io.github.mikel0ne.l1copilot.domain.Ticket;
import io.github.mikel0ne.l1copilot.domain.TicketStatus;
import io.github.mikel0ne.l1copilot.domain.repository.OrderRepository;
import io.github.mikel0ne.l1copilot.domain.repository.TicketRepository;
import io.github.mikel0ne.l1copilot.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({TicketController.class, ReturnController.class})
@Import(SessionUsers.class)
class OwnedResourceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private TicketRepository ticketRepository;

    @MockitoBean
    private OrderRepository orderRepository;

    @BeforeEach
    void knownUsers() {
        when(userRepository.existsById("u-001")).thenReturn(true);
        when(userRepository.existsById("u-002")).thenReturn(true);
    }

    @Test
    @DisplayName("A ticket is returned to its owner")
    void ownerReadsTicket() throws Exception {
        when(ticketRepository.findByIdAndUserId(1L, "u-001")).thenReturn(Optional.of(ticket(1L, "u-001")));

        mockMvc.perform(get("/copilot/tickets/1").header(SessionUsers.HEADER, "u-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("Another user's ticket is a 404, not a leak")
    void otherUsersTicketIsNotFound() throws Exception {
        when(ticketRepository.findByIdAndUserId(1L, "u-002")).thenReturn(Optional.empty());

        mockMvc.perform(get("/copilot/tickets/1").header(SessionUsers.HEADER, "u-002"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Without a session header the ticket detail is 401")
    void ticketWithoutSessionIsUnauthorized() throws Exception {
        mockMvc.perform(get("/copilot/tickets/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("The return label exists only for the owner's order with a return in progress")
    void returnLabelRequiresReturnInProgress() throws Exception {
        when(orderRepository.findByIdAndUserId(10L, "u-001"))
                .thenReturn(Optional.of(order(10L, "u-001", OrderStatus.RETURN_REQUESTED)));
        when(orderRepository.findByIdAndUserId(11L, "u-001"))
                .thenReturn(Optional.of(order(11L, "u-001", OrderStatus.PAID)));

        mockMvc.perform(get("/copilot/returns/10").header(SessionUsers.HEADER, "u-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10));
        mockMvc.perform(get("/copilot/returns/11").header(SessionUsers.HEADER, "u-001"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Another user's return label is a 404")
    void otherUsersReturnLabelIsNotFound() throws Exception {
        when(orderRepository.findByIdAndUserId(10L, "u-002")).thenReturn(Optional.empty());

        mockMvc.perform(get("/copilot/returns/10").header(SessionUsers.HEADER, "u-002"))
                .andExpect(status().isNotFound());
    }

    private static Ticket ticket(long id, String userId) {
        Ticket t = new Ticket();
        t.setId(id);
        t.setUserId(userId);
        t.setSubject("Subject");
        t.setDescription("Description");
        t.setStatus(TicketStatus.OPEN);
        t.setCreatedAt(Instant.parse("2026-06-01T10:00:00Z"));
        return t;
    }

    private static Order order(long id, String userId, OrderStatus status) {
        Order o = new Order();
        o.setId(id);
        o.setUserId(userId);
        o.setDescription("Order");
        o.setStatus(status);
        o.setOrderDate(LocalDate.parse("2026-06-01"));
        return o;
    }
}
