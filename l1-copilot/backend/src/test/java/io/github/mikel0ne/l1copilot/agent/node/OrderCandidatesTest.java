package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.dto.result.OrderSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderCandidatesTest {

    @Test
    @DisplayName("Describes an order with its date as the router prompt shows it")
    void describesWithDate() {
        OrderSummary order = new OrderSummary(123L, "Portátil", "IN_DELIVERY", LocalDate.of(2026, 6, 1), null);

        assertThat(OrderCandidates.describe(order)).isEqualTo("#123 Portátil (IN_DELIVERY, pedido el 2026-06-01)");
    }

    @Test
    @DisplayName("Describes an order without a date")
    void describesWithoutDate() {
        OrderSummary order = new OrderSummary(123L, "Portátil", "IN_DELIVERY", null, null);

        assertThat(OrderCandidates.describe(order)).isEqualTo("#123 Portátil (IN_DELIVERY)");
    }

    @Test
    @DisplayName("An order is listed when a candidate starts with its exact id")
    void listsExactId() {
        assertThat(OrderCandidates.lists(List.of("#123 Portátil (X)"), 123)).isTrue();
    }

    @Test
    @DisplayName("An id that is only a prefix of another one is not listed")
    void doesNotListIdPrefix() {
        assertThat(OrderCandidates.lists(List.of("#1234 Portátil (X)"), 123)).isFalse();
    }

    @Test
    @DisplayName("Nothing is listed without candidates")
    void emptyListsNothing() {
        assertThat(OrderCandidates.lists(List.of(), 123)).isFalse();
    }
}
