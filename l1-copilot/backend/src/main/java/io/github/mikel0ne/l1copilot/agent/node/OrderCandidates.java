package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.dto.result.OrderSummary;

import java.util.List;

final class OrderCandidates {

    private OrderCandidates() {
    }

    static String describe(OrderSummary order) {
        return String.format("#%d %s (%s%s)",
                order.id(), order.description(), order.status(),
                order.orderDate() != null ? ", pedido el " + order.orderDate() : "");
    }

    static boolean lists(List<String> candidates, long orderId) {
        String prefix = "#" + orderId + " ";
        return candidates.stream().anyMatch(c -> c.startsWith(prefix));
    }
}
