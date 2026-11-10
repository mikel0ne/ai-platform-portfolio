package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.dto.ReturnCardDto;
import io.github.mikel0ne.l1copilot.dto.result.ReturnResult;
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
public class ReturnExecNode implements NodeAction<SupportState> {

    private final OrderService orderService;

    @Override
    public Map<String, Object> apply(SupportState state) {
        Long orderId = state.orderId()
                .orElseThrow(() -> new IllegalStateException("return_exec without a resolved orderId"));
        log.debug("---RETURN EXEC--- orderId={}", orderId);

        ReturnResult result = orderService.requestReturn(orderId, state.userId());

        Map<String, Object> updates = new HashMap<>();
        updates.put("confirmation", "NONE");
        updates.put("awaiting", Awaiting.NOTHING.name());

        if (!result.returned()) {
            updates.put("toolResult", result.reason());
            updates.put("resolved", false);
            return updates;
        }

        String code = result.reference();
        Map<String, Object> card = new HashMap<>();
        card.put("id", orderId);
        card.put("code", code);
        card.put("description", orderService.findOrder(orderId, state.userId())
                .map(o -> o.description()).orElse("Devolución"));
        card.put("status", "RETURN_REQUESTED");
        card.put("createdAt", Instant.now().toString());

        updates.put("toolResult", String.format(
                "Devolución %s iniciada para el pedido #%d. Hay que informar al cliente de que su "
                + "devolución ya se ha iniciado y que, justo debajo, la interfaz muestra un enlace a "
                + "la etiqueta de devolución que puede abrir para imprimirla y entregar el paquete al "
                + "transportista. No inventes plazos ni datos.", code, orderId));
        updates.put("returnCards", List.of(card));
        updates.put("resolved", true);

        updates.put("orderId", AgentState.MARK_FOR_REMOVAL);
        updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
        return updates;
    }
}
