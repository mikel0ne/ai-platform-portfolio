package io.github.mikel0ne.l1copilot.controller;

import io.github.mikel0ne.l1copilot.domain.OrderStatus;
import io.github.mikel0ne.l1copilot.domain.repository.OrderRepository;
import io.github.mikel0ne.l1copilot.dto.ReturnLabelDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Returns", description = "Return label of an order")
@RestController
@RequestMapping("/copilot/returns")
@RequiredArgsConstructor
public class ReturnController {

    private final OrderRepository orderRepository;
    private final SessionUsers sessionUsers;

    @Operation(summary = "Return label of one of the session user's orders with a return in progress")
    @GetMapping("/{id}")
    public ReturnLabelDto returnLabel(@RequestHeader(SessionUsers.HEADER) String userId, @PathVariable Long id) {
        String owner = sessionUsers.requireKnown(userId);
        return orderRepository.findByIdAndUserId(id, owner)
                .filter(order -> order.getStatus() == OrderStatus.RETURN_REQUESTED)
                .map(ReturnLabelDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("Return of order", id));
    }
}
