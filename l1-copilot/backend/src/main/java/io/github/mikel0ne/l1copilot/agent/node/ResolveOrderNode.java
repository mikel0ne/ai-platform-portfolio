package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.dto.result.OrderSummary;
import io.github.mikel0ne.l1copilot.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ResolveOrderNode implements NodeAction<SupportState> {

    private final OrderService orderService;

    @Override
    public Map<String, Object> apply(SupportState state) {
        log.debug("---RESOLVE ORDER---");
        List<OrderSummary> recent = orderService.getRecentOrders(state.userId());
        List<String> candidates = recent.stream()
                .map(ResolveOrderNode::describeCandidate)
                .toList();
        List<Map<String, Object>> orderCards = recent.stream()
                .map(o -> Map.<String, Object>of(
                        "id", o.id(),
                        "description", o.description(),
                        "status", o.status(),
                        "imageUrl", o.imageUrl() != null ? o.imageUrl() : ""))
                .toList();

        if (recent.isEmpty()) {
            return Map.of(
                    "toolResult", "El usuario no tiene ningún pedido registrado.",
                    "awaiting", Awaiting.NOTHING.name());
        }

        if (recent.size() == 1) {
            log.debug("---RESOLVE ORDER--- single candidate: {}", recent.get(0).id());
            return Map.of(
                    "orderId", recent.get(0).id(),
                    "candidates", candidates,
                    "orderCards", orderCards,
                    "awaiting", Awaiting.NOTHING.name());
        }

        String listing = String.join("; ", candidates);
        log.debug("---RESOLVE ORDER--- {} candidates, asking the user to choose", recent.size());
        return Map.of(
                "candidates", candidates,
                "orderCards", orderCards,
                "awaiting", Awaiting.ORDER_CHOICE.name(),
                "toolResult", String.format(
                        "El usuario tiene varios pedidos recientes: %s. Hay que preguntarle a cuál de "
                        + "ellos se refiere la gestión en curso (sobre cuál quiere actuar, o cuál es "
                        + "el del problema si está reportando una incidencia).", listing));
    }

    private static String describeCandidate(OrderSummary order) {
        return String.format("#%d %s (%s%s)",
                order.id(), order.description(), order.status(),
                order.orderDate() != null ? ", pedido el " + order.orderDate() : "");
    }
}
