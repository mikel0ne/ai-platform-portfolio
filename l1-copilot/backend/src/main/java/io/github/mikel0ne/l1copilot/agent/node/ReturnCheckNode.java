package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.domain.OrderStatus;
import io.github.mikel0ne.l1copilot.dto.ReturnCardDto;
import io.github.mikel0ne.l1copilot.dto.result.ReturnCheck;
import io.github.mikel0ne.l1copilot.service.OrderNotFoundException;
import io.github.mikel0ne.l1copilot.service.OrderService;
import io.github.mikel0ne.l1copilot.dto.result.OrderSummary;
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
public class ReturnCheckNode implements NodeAction<SupportState> {

    private final OrderService orderService;

    @Override
    public Map<String, Object> apply(SupportState state) {
        Long orderId = state.orderId()
                .orElseThrow(() -> new IllegalStateException("return_check without a resolved orderId"));
        log.debug("---RETURN CHECK--- orderId={} confirmation={}", orderId, state.confirmation());

        Map<String, Object> updates = new HashMap<>();
        ReturnCheck check;
        try {
            check = orderService.checkReturnable(orderId, state.userId());
        } catch (OrderNotFoundException e) {
            updates.put(SupportState.TOOL_RESULT, String.format(
                    "No existe el pedido #%d entre los pedidos del usuario.", orderId));
            updates.put(SupportState.ORDER_ID, AgentState.MARK_FOR_REMOVAL);
            updates.put(SupportState.CANDIDATES, AgentState.MARK_FOR_REMOVAL);
            updates.put(SupportState.RETURNABLE, false);
            updates.put(SupportState.AWAITING, Awaiting.NOTHING.name());
            return updates;
        }

        updates.put(SupportState.RETURNABLE, check.returnable());

        if (!check.returnable()) {
            OrderStatus status = parseStatus(check.status());
            boolean alreadyReturning = status == OrderStatus.RETURN_REQUESTED;
            boolean cancelled = status == OrderStatus.CANCELLED;
            if (alreadyReturning) {
                updates.put(SupportState.RETURN_CARDS, List.of(returnCard(orderId, state.userId())));
                updates.put(SupportState.TOOL_RESULT, String.format(
                        "Hechos sobre el pedido #%d que hay que transmitir al cliente: su devolución "
                        + "YA está iniciada, así que no procede volver a solicitarla. Justo debajo la "
                        + "interfaz muestra un enlace a la etiqueta de devolución que el usuario puede "
                        + "abrir para consultarla o reimprimirla. Si describe un problema concreto con "
                        + "el proceso o el producto, la vía es abrir una incidencia, no una nueva "
                        + "devolución.", orderId));
            } else if (cancelled) {
                updates.put(SupportState.TOOL_RESULT, String.format(
                        "Hechos sobre el pedido #%d: está cancelado, por lo que no procede devolverlo "
                        + "(no llegó a entregarse). Atiende cualquier otra duda que tenga.", orderId));
            } else {
                updates.put(SupportState.TOOL_RESULT, String.format(
                        "%s. Hechos que hay que transmitir al cliente: la devolución solo es posible "
                        + "una vez el pedido se ha ENTREGADO; todavía no lo está, así que la vía es "
                        + "esperar a recibirlo y, ya entregado, solicitar la devolución. No prometas "
                        + "plazos ni inventes datos.", check.description()));
            }
            updates.put(SupportState.CANDIDATES, AgentState.MARK_FOR_REMOVAL);
            updates.put(SupportState.AWAITING, Awaiting.NOTHING.name());
            updates.put(SupportState.RESOLVED, true);
            return updates;
        }

        switch (state.confirmation()) {
            case YES ->

                updates.put(SupportState.AWAITING, Awaiting.NOTHING.name());
            case NO -> {
                updates.put(SupportState.TOOL_RESULT, String.format(
                        "El usuario ha decidido NO devolver el pedido #%d. La devolución queda "
                        + "descartada.", orderId));
                updates.put(SupportState.CANDIDATES, AgentState.MARK_FOR_REMOVAL);
                updates.put(SupportState.AWAITING, Awaiting.NOTHING.name());
                updates.put(SupportState.RESOLVED, true);
            }
            default -> {
                updates.put(SupportState.TOOL_RESULT, String.format(
                        "%s. Hay que pedir al usuario que confirme con el botón que quiere iniciar la "
                        + "devolución de este pedido.", check.description()));
                updates.put(SupportState.AWAITING, Awaiting.RETURN_CONFIRM.name());
                updates.put(SupportState.PENDING_RETURN_ID, orderId);
            }
        }
        return updates;
    }

    private static OrderStatus parseStatus(String raw) {
        try {
            return OrderStatus.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private ReturnCardDto returnCard(Long orderId, String userId) {
        return ReturnCardDto.requested(orderId, orderService.findOrder(orderId, userId)
                .map(OrderSummary::description).orElse("Devolución"));
    }
}
