package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.domain.OrderStatus;
import io.github.mikel0ne.l1copilot.dto.ReturnCardDto;
import io.github.mikel0ne.l1copilot.dto.result.ShipmentStatus;
import io.github.mikel0ne.l1copilot.service.OrderNotFoundException;
import io.github.mikel0ne.l1copilot.service.OrderService;
import io.github.mikel0ne.l1copilot.dto.OrderCardDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

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
            updates.put(SupportState.RESOLVED, true);

            updates.put(SupportState.CANDIDATES, AgentState.MARK_FOR_REMOVAL);
            updates.put(SupportState.AWAITING, Awaiting.NOTHING.name());

            if (OrderStatus.RETURN_REQUESTED.name().equals(s.status())) {
                updates.put(SupportState.RETURN_CARDS, List.of(returnCard(orderId, s.description())));
                updates.put(SupportState.TOOL_RESULT, String.format(
                        "El pedido #%d '%s' tiene una DEVOLUCIÓN ya iniciada. El envío original ya se "
                        + "entregó, así que NO menciones su fecha de entrega: lo relevante ahora es la "
                        + "etiqueta de devolución, cuyo enlace muestra la interfaz justo debajo de este "
                        + "mensaje para que el usuario la abra o reimprima.", s.orderId(), s.description()));
            } else {
                updates.put(SupportState.ORDER_CARDS,
                        List.of(new OrderCardDto(s.orderId(), s.description(), s.status(), s.imageUrl())));
                updates.put(SupportState.TOOL_RESULT, String.format("Pedido #%d '%s': estado %s",
                        s.orderId(), s.description(), s.status())
                        + (s.estimatedDelivery() != null ? ", entrega estimada el " + s.estimatedDelivery() : "")
                        + (s.carrier() != null ? ", transportista " + s.carrier() : ""));
            }
            return updates;
        } catch (OrderNotFoundException e) {
            log.debug("---TRACKING--- order not found: {}", orderId);

            return Map.of(
                    SupportState.TOOL_RESULT, String.format(
                            "No existe el pedido #%d entre los pedidos del usuario.", orderId),
                    SupportState.ORDER_ID, AgentState.MARK_FOR_REMOVAL,
                    SupportState.CANDIDATES, AgentState.MARK_FOR_REMOVAL,
                    SupportState.AWAITING, Awaiting.NOTHING.name());
        }
    }

    private ReturnCardDto returnCard(Long orderId, String description) {
        return ReturnCardDto.requested(orderId, description);
    }
}
