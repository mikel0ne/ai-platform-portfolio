package io.github.mikel0ne.l1copilot.service;

import io.github.mikel0ne.l1copilot.domain.Order;
import io.github.mikel0ne.l1copilot.domain.OrderStatus;
import io.github.mikel0ne.l1copilot.domain.repository.OrderRepository;
import io.github.mikel0ne.l1copilot.dto.ReturnCardDto;
import io.github.mikel0ne.l1copilot.dto.result.CancellationCheck;
import io.github.mikel0ne.l1copilot.dto.result.CancellationResult;
import io.github.mikel0ne.l1copilot.dto.result.OrderSummary;
import io.github.mikel0ne.l1copilot.dto.result.ReturnCheck;
import io.github.mikel0ne.l1copilot.dto.result.ReturnResult;
import io.github.mikel0ne.l1copilot.dto.result.ShipmentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public List<OrderSummary> getRecentOrders(String userId) {
        return orderRepository.findTop3ByUserIdOrderByOrderDateDesc(userId).stream()
                .map(o -> new OrderSummary(o.getId(), o.getDescription(),
                        o.getStatus().name(), o.getOrderDate(), o.getImageUrl()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<OrderSummary> findOrder(Long orderId, String userId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .map(o -> new OrderSummary(o.getId(), o.getDescription(),
                        o.getStatus().name(), o.getOrderDate(), o.getImageUrl()));
    }

    @Transactional(readOnly = true)
    public ShipmentStatus trackShipment(Long orderId, String userId) {
        Order o = ownedOrder(orderId, userId);
        return new ShipmentStatus(o.getId(), o.getDescription(), o.getStatus().name(),
                o.getEstimatedDelivery(), o.getTrackingCarrier(), o.getImageUrl());
    }

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
