package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.dto.tools.ShipmentStatus;
import io.github.mikel0ne.l1copilot.tools.OrderNotFoundException;
import io.github.mikel0ne.l1copilot.tools.OrderTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Skill TRACKING: consulta el estado de envío del pedido ya resuelto.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrackingNode implements NodeAction<SupportState> {

    private final OrderTools orderTools;

    @Override
    public Map<String, Object> apply(SupportState state) {
        Long orderId = state.orderId()
                .orElseThrow(() -> new IllegalStateException("tracking sin orderId resuelto"));
        log.debug("---TRACKING--- orderId={}", orderId);

        try {
            ShipmentStatus s = orderTools.trackShipment(orderId, state.userId());
            String result = "Pedido #" + s.orderId() + " '" + s.description() + "': estado "
                    + s.status()
                    + (s.estimatedDelivery() != null ? ", entrega estimada el " + s.estimatedDelivery() : "")
                    + (s.carrier() != null ? ", transportista " + s.carrier() : "");
            return Map.of(
                    "toolResult", result,
                    "resolved", true,
                    "awaiting", Awaiting.NOTHING.name());
        } catch (OrderNotFoundException e) {
            log.debug("---TRACKING--- pedido no encontrado: {}", orderId);
            // El id era inválido o ajeno: se descarta para re-desambiguar en el siguiente turno
            return Map.of(
                    "toolResult", "No existe el pedido #" + orderId + " entre los pedidos del usuario.",
                    "orderId", AgentState.MARK_FOR_REMOVAL,
                    "awaiting", Awaiting.NOTHING.name());
        }
    }
}
