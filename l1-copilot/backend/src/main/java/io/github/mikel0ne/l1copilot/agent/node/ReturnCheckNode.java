package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.domain.OrderStatus;
import io.github.mikel0ne.l1copilot.dto.ReturnCardDto;
import io.github.mikel0ne.l1copilot.dto.tools.ReturnCheck;
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

@Component
@RequiredArgsConstructor
@Slf4j
public class ReturnCheckNode implements NodeAction<SupportState> {

    private final OrderTools orderTools;

    @Override
    public Map<String, Object> apply(SupportState state) {
        Long orderId = state.orderId()
                .orElseThrow(() -> new IllegalStateException("return_check without a resolved orderId"));
        log.debug("---RETURN CHECK--- orderId={} confirmation={}", orderId, state.confirmation());

        Map<String, Object> updates = new HashMap<>();
        ReturnCheck check;
        try {
            check = orderTools.checkReturnable(orderId, state.userId());
        } catch (OrderNotFoundException e) {
            updates.put("toolResult", String.format(
                    "No existe el pedido #%d entre los pedidos del usuario.", orderId));
            updates.put("orderId", AgentState.MARK_FOR_REMOVAL);
            updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
            updates.put("returnable", false);
            updates.put("awaiting", Awaiting.NOTHING.name());
            return updates;
        }

        updates.put("returnable", check.returnable());

        if (!check.returnable()) {
            OrderStatus status = parseStatus(check.status());
            boolean alreadyReturning = status == OrderStatus.RETURN_REQUESTED;
            boolean cancelled = status == OrderStatus.CANCELLED;
            if (alreadyReturning) {
                updates.put("returnCards", List.of(returnCard(orderId, state.userId())));
                updates.put("toolResult", String.format(
                        "Hechos sobre el pedido #%d que hay que transmitir al cliente: su devolución "
                        + "YA está iniciada, así que no procede volver a solicitarla. Justo debajo la "
                        + "interfaz muestra un enlace a la etiqueta de devolución que el usuario puede "
                        + "abrir para consultarla o reimprimirla. Si describe un problema concreto con "
                        + "el proceso o el producto, la vía es abrir una incidencia, no una nueva "
                        + "devolución.", orderId));
            } else if (cancelled) {
                updates.put("toolResult", String.format(
                        "Hechos sobre el pedido #%d: está cancelado, por lo que no procede devolverlo "
                        + "(no llegó a entregarse). Atiende cualquier otra duda que tenga.", orderId));
            } else {
                updates.put("toolResult", String.format(
                        "%s. Hechos que hay que transmitir al cliente: la devolución solo es posible "
                        + "una vez el pedido se ha ENTREGADO; todavía no lo está, así que la vía es "
                        + "esperar a recibirlo y, ya entregado, solicitar la devolución. No prometas "
                        + "plazos ni inventes datos.", check.description()));
            }
            updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
            updates.put("awaiting", Awaiting.NOTHING.name());
            updates.put("resolved", true);
            return updates;
        }

        switch (state.confirmation()) {
            case "YES" ->

                updates.put("awaiting", Awaiting.NOTHING.name());
            case "NO" -> {
                updates.put("toolResult", String.format(
                        "El usuario ha decidido NO devolver el pedido #%d. La devolución queda "
                        + "descartada.", orderId));
                updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
                updates.put("awaiting", Awaiting.NOTHING.name());
                updates.put("resolved", true);
            }
            default -> {
                updates.put("toolResult", String.format(
                        "%s. Hay que pedir al usuario que confirme con el botón que quiere iniciar la "
                        + "devolución de este pedido.", check.description()));
                updates.put("awaiting", Awaiting.RETURN_CONFIRM.name());
                updates.put("pendingReturnId", orderId);
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

    private Map<String, Object> returnCard(Long orderId, String userId) {
        Map<String, Object> card = new HashMap<>();
        card.put("id", orderId);
        card.put("code", ReturnCardDto.code(orderId));
        card.put("description", orderTools.findOrder(orderId, userId)
                .map(o -> o.description()).orElse("Devolución"));
        card.put("status", OrderStatus.RETURN_REQUESTED.name());
        card.put("createdAt", Instant.now().toString());
        return card;
    }
}
