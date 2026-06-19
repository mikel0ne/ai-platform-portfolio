package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.domain.OrderStatus;
import io.github.mikel0ne.l1copilot.dto.ReturnCardDto;
import io.github.mikel0ne.l1copilot.dto.tools.ShipmentStatus;
import io.github.mikel0ne.l1copilot.tools.OrderNotFoundException;
import io.github.mikel0ne.l1copilot.tools.OrderTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill TRACKING: consulta el estado de envío del pedido ya resuelto.
 * <p>
 * Caso especial: si el pedido está en devolución ({@code RETURN_REQUESTED}), el
 * envío original ya no es lo relevante; el nodo surface la <b>etiqueta de
 * devolución</b> (tarjeta-enlace) para que el usuario pueda abrirla. Así la
 * etiqueta es accesible de forma determinista sea cual sea la intención que el
 * LLM detecte ("ver etiqueta", clic en la ficha del pedido, etc.).
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

            Map<String, Object> updates = new HashMap<>();
            updates.put("resolved", true);
            // El menú de desambiguación ya cumplió su función: se descarta para que el
            // router no pueda "reelegir" otro pedido del listado en el siguiente turno.
            updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
            updates.put("awaiting", Awaiting.NOTHING.name());

            if (OrderStatus.RETURN_REQUESTED.name().equals(s.status())) {
                // Pedido en devolución: lo relevante es la ETIQUETA, no el envío original ya
                // entregado ni la ficha de seguimiento. Se muestra SOLO la tarjeta-enlace a la
                // etiqueta (lo que el usuario espera al pinchar un pedido "en devolución") y se
                // indica al redactor que NO insista en la fecha de entrega original.
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
            log.debug("---TRACKING--- pedido no encontrado: {}", orderId);
            // El id era inválido o ajeno: se descarta para re-desambiguar en el siguiente turno
            return Map.of(
                    "toolResult", String.format(
                            "No existe el pedido #%d entre los pedidos del usuario.", orderId),
                    "orderId", AgentState.MARK_FOR_REMOVAL,
                    "candidates", AgentState.MARK_FOR_REMOVAL,
                    "awaiting", Awaiting.NOTHING.name());
        }
    }

    /** Tarjeta-enlace a la etiqueta de devolución de un pedido en devolución. */
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
