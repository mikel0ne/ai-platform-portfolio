package io.github.mikel0ne.l1copilot.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.mikel0ne.l1copilot.domain.Order;
import io.github.mikel0ne.l1copilot.domain.OrderStatus;
import io.github.mikel0ne.l1copilot.domain.repository.OrderRepository;
import io.github.mikel0ne.l1copilot.dto.tools.CancellationCheck;
import io.github.mikel0ne.l1copilot.dto.tools.CancellationResult;
import io.github.mikel0ne.l1copilot.dto.tools.OrderSummary;
import io.github.mikel0ne.l1copilot.dto.tools.ShipmentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Herramientas deterministas sobre pedidos. Los nodos del grafo las invocan
 * explícitamente (no hay function-calling autónomo del LLM): el modelo decide
 * el flujo, pero la consulta, las reglas de negocio y la seguridad viven aquí.
 * <p>
 * Las anotaciones {@code @Tool}/{@code @P} documentan el contrato y permiten,
 * como extensión futura, registrarlas en un {@code AiServices} con tool-calling.
 * <p>
 * Seguridad: el {@code userId} llega siempre del estado del grafo (fijado desde
 * la sesión HTTP), nunca de la salida del LLM.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderTools {

    private final OrderRepository orderRepository;

    @Tool("Obtiene una lista de los últimos 3 pedidos del usuario para ayudarle a elegir")
    @Transactional(readOnly = true)
    public List<OrderSummary> getRecentOrders(@P("ID del usuario logueado") String userId) {
        return orderRepository.findTop3ByUserIdOrderByOrderDateDesc(userId).stream()
                .map(o -> new OrderSummary(o.getId(), o.getDescription(),
                        o.getStatus().name(), o.getOrderDate()))
                .toList();
    }

    @Tool("Obtiene el estado de envío de un pedido específico del usuario")
    @Transactional(readOnly = true)
    public ShipmentStatus trackShipment(@P("El ID exacto del pedido") Long orderId, String userId) {
        Order o = ownedOrder(orderId, userId);
        return new ShipmentStatus(o.getId(), o.getDescription(), o.getStatus().name(),
                o.getEstimatedDelivery(), o.getTrackingCarrier());
    }

    @Tool("Comprueba si un pedido puede cancelarse según las reglas de negocio, sin cancelarlo")
    @Transactional(readOnly = true)
    public CancellationCheck checkCancellable(Long orderId, String userId) {
        Order o = ownedOrder(orderId, userId);
        String description = o.getStatus().isCancellable()
                ? "El pedido '" + o.getDescription() + "' está en estado " + o.getStatus()
                        + " y puede cancelarse"
                : "El pedido '" + o.getDescription() + "' está en estado " + o.getStatus()
                        + " y ya no puede cancelarse";
        return new CancellationCheck(orderId, o.getStatus().isCancellable(),
                o.getStatus().name(), description);
    }

    @Tool("Cancela un pedido del usuario si las reglas de negocio lo permiten")
    @Transactional
    public CancellationResult cancelOrder(Long orderId, String userId) {
        Order o = ownedOrder(orderId, userId);
        // Revalida en el momento de ejecutar: el estado pudo cambiar entre turnos
        if (!o.getStatus().isCancellable()) {
            return new CancellationResult(orderId, false,
                    "Rechazada: el pedido está en estado " + o.getStatus());
        }
        o.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(o);
        log.info("Pedido {} cancelado por el usuario {}", orderId, userId);
        return new CancellationResult(orderId, true,
                "El pedido '" + o.getDescription() + "' se ha cancelado correctamente");
    }

    private Order ownedOrder(Long orderId, String userId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
