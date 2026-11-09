package io.github.mikel0ne.l1copilot.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.mikel0ne.l1copilot.domain.Order;
import io.github.mikel0ne.l1copilot.domain.OrderStatus;
import io.github.mikel0ne.l1copilot.domain.repository.OrderRepository;
import io.github.mikel0ne.l1copilot.dto.ReturnCardDto;
import io.github.mikel0ne.l1copilot.dto.tools.CancellationCheck;
import io.github.mikel0ne.l1copilot.dto.tools.CancellationResult;
import io.github.mikel0ne.l1copilot.dto.tools.OrderSummary;
import io.github.mikel0ne.l1copilot.dto.tools.ReturnCheck;
import io.github.mikel0ne.l1copilot.dto.tools.ReturnResult;
import io.github.mikel0ne.l1copilot.dto.tools.ShipmentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
                        o.getStatus().name(), o.getOrderDate(), o.getImageUrl()))
                .toList();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<OrderSummary> findOrder(Long orderId, String userId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .map(o -> new OrderSummary(o.getId(), o.getDescription(),
                        o.getStatus().name(), o.getOrderDate(), o.getImageUrl()));
    }

    @Tool("Obtiene el estado de envío de un pedido específico del usuario")
    @Transactional(readOnly = true)
    public ShipmentStatus trackShipment(@P("El ID exacto del pedido") Long orderId, String userId) {
        Order o = ownedOrder(orderId, userId);
        return new ShipmentStatus(o.getId(), o.getDescription(), o.getStatus().name(),
                o.getEstimatedDelivery(), o.getTrackingCarrier(), o.getImageUrl());
    }

    @Tool("Comprueba si un pedido puede cancelarse según las reglas de negocio, sin cancelarlo")
    @Transactional(readOnly = true)
    public CancellationCheck checkCancellable(Long orderId, String userId) {
        Order o = ownedOrder(orderId, userId);
        String description = o.getStatus().isCancellable()
                ? String.format("El pedido '%s' está en estado %s y puede cancelarse",
                        o.getDescription(), o.getStatus())
                : String.format("El pedido '%s' está en estado %s y ya no puede cancelarse",
                        o.getDescription(), o.getStatus());
        return new CancellationCheck(orderId, o.getStatus().isCancellable(),
                o.getStatus().name(), description);
    }

    @Tool("Cancela un pedido del usuario si las reglas de negocio lo permiten")
    @Transactional
    public CancellationResult cancelOrder(Long orderId, String userId) {
        Order o = ownedOrder(orderId, userId);

        if (!o.getStatus().isCancellable()) {
            return new CancellationResult(orderId, false, String.format(
                    "Rechazada: el pedido está en estado %s", o.getStatus()));
        }
        o.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(o);
        log.info("Order {} cancelled by user {}", orderId, userId);
        return new CancellationResult(orderId, true, String.format(
                "El pedido '%s' se ha cancelado correctamente", o.getDescription()));
    }

    @Tool("Comprueba si un pedido admite devolución según las reglas de negocio, sin iniciarla")
    @Transactional(readOnly = true)
    public ReturnCheck checkReturnable(Long orderId, String userId) {
        Order o = ownedOrder(orderId, userId);
        String description = o.getStatus().isReturnable()
                ? String.format("El pedido '%s' está en estado %s y admite devolución",
                        o.getDescription(), o.getStatus())
                : String.format("El pedido '%s' está en estado %s y no admite una devolución",
                        o.getDescription(), o.getStatus());
        return new ReturnCheck(orderId, o.getStatus().isReturnable(),
                o.getStatus().name(), description);
    }

    @Tool("Inicia la devolución de un pedido del usuario si las reglas de negocio lo permiten")
    @Transactional
    public ReturnResult requestReturn(Long orderId, String userId) {
        Order o = ownedOrder(orderId, userId);

        if (!o.getStatus().isReturnable()) {
            return new ReturnResult(orderId, false, null, String.format(
                    "Rechazada: el pedido está en estado %s", o.getStatus()));
        }
        o.setStatus(OrderStatus.RETURN_REQUESTED);
        orderRepository.save(o);
        String reference = ReturnCardDto.code(orderId);
        log.info("Return of order {} started by user {}", orderId, userId);
        return new ReturnResult(orderId, true, reference, String.format(
                "La devolución del pedido '%s' se ha iniciado correctamente", o.getDescription()));
    }

    private Order ownedOrder(Long orderId, String userId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
