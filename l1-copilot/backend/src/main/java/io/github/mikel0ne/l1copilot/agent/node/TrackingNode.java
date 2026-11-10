package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.domain.OrderStatus;
import io.github.mikel0ne.l1copilot.dto.ReturnCardDto;
import io.github.mikel0ne.l1copilot.dto.result.ShipmentStatus;
import io.github.mikel0ne.l1copilot.service.OrderNotFoundException;
import io.github.mikel0ne.l1copilot.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class TrackingNode implements NodeAction<SupportState> {

    private final OrderService orderService;

    @Override
    public Map<String, Object> apply(SupportState state) {
        Long orderId = state.orderId()
                .orElseThrow(() -> new IllegalStateException("tracking without a resolved orderId"));
        log.debug("---TRACKING--- orderId={}", orderId);

        try {
            ShipmentStatus s = orderService.trackShipment(orderId, state.userId());

            Map<String, Object> updates = new HashMap<>();
            updates.put("resolved", true);

            updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
            updates.put("awaiting", Awaiting.NOTHING.name());

            if (OrderStatus.RETURN_REQUESTED.name().equals(s.status())) {
                updates.put("returnCards", List.of(returnCard(orderId, s.description())));
                updates.put("toolResult", String.format(
                        "El pedido #%d '%s' tiene una DEVOLUCIÓN ya iniciada. El envío original ya se "
                        + "entregó, así que NO menciones su fecha de entrega: lo relevante ahora es la "
                        + "etiqueta de devolución, cuyo enlace muestra la interfaz justo debajo de este "
                        + "mensaje para que el usuario la abra o reimprima.", s.orderId(), s.description()));
            } else {
                Map<String, Object> card = new HashMap<>();
                card.put("id", s.orderId());
                card.put("description", s.description());
                card.put("status", s.status());
                card.put("imageUrl", s.imageUrl() != null ? s.imageUrl() : "");
                updates.put("orderCards", List.of(card));
                updates.put("toolResult", String.format("Pedido #%d '%s': estado %s",
                        s.orderId(), s.description(), s.status())
                        + (s.estimatedDelivery() != null ? ", entrega estimada el " + s.estimatedDelivery() : "")
                        + (s.carrier() != null ? ", transportista " + s.carrier() : ""));
            }
            return updates;
        } catch (OrderNotFoundException e) {
            log.debug("---TRACKING--- order not found: {}", orderId);

            return Map.of(
                    "toolResult", String.format(
                            "No existe el pedido #%d entre los pedidos del usuario.", orderId),
                    "orderId", AgentState.MARK_FOR_REMOVAL,
                    "candidates", AgentState.MARK_FOR_REMOVAL,
                    "awaiting", Awaiting.NOTHING.name());
        }
    }

    private Map<String, Object> returnCard(Long orderId, String description) {
        Map<String, Object> card = new HashMap<>();
        card.put("id", orderId);
        card.put("code", ReturnCardDto.code(orderId));
        card.put("description", description);
        card.put("status", OrderStatus.RETURN_REQUESTED.name());
        card.put("createdAt", Instant.now().toString());
        return card;
    }
}
