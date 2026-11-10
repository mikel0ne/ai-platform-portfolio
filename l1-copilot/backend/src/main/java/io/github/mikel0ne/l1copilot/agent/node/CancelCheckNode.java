package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.domain.OrderStatus;
import io.github.mikel0ne.l1copilot.dto.result.CancellationCheck;
import io.github.mikel0ne.l1copilot.service.OrderNotFoundException;
import io.github.mikel0ne.l1copilot.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class CancelCheckNode implements NodeAction<SupportState> {

    private final OrderService orderService;

    @Override
    public Map<String, Object> apply(SupportState state) {
        Long orderId = state.orderId()
                .orElseThrow(() -> new IllegalStateException("cancel_check without a resolved orderId"));
        log.debug("---CANCEL CHECK--- orderId={} confirmation={}", orderId, state.confirmation());

        Map<String, Object> updates = new HashMap<>();
        CancellationCheck check;
        try {
            check = orderService.checkCancellable(orderId, state.userId());
        } catch (OrderNotFoundException e) {
            updates.put("toolResult", String.format(
                    "No existe el pedido #%d entre los pedidos del usuario.", orderId));
            updates.put("orderId", AgentState.MARK_FOR_REMOVAL);
            updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
            updates.put("cancellable", false);
            updates.put("awaiting", Awaiting.NOTHING.name());
            return updates;
        }

        updates.put("cancellable", check.cancellable());

        if (!check.cancellable()) {
            boolean alreadyCancelled = OrderStatus.CANCELLED.name().equals(check.status());
            if (alreadyCancelled) {
                updates.put("toolResult", String.format(
                        "Hechos sobre el pedido #%d que hay que transmitir al cliente: ya está "
                        + "cancelado; la cancelación es una acción irreversible que no puede "
                        + "repetirse, así que no procede volver a cancelarlo. El cliente puede "
                        + "necesitar información (por ejemplo sobre el reembolso o las políticas de "
                        + "cancelación) o tener algún problema con esa cancelación; quédate a su "
                        + "disposición para ayudarle con ello, sin dar por hecho que quiere abrir "
                        + "una incidencia ni ofrecérsela salvo que la pida.", orderId));
            } else if (OrderStatus.DELIVERED.name().equals(check.status())) {
                updates.put("toolResult", String.format(
                        "Hechos sobre el pedido #%d que hay que transmitir al cliente: ya está "
                        + "ENTREGADO, por lo que no es posible cancelarlo. Lo que procede en su lugar "
                        + "es una DEVOLUCIÓN: explícaselo y ofrécete a iniciar la devolución de este "
                        + "pedido (no una incidencia). No prometas plazos ni inventes datos.", orderId));
            } else {
                updates.put("toolResult", check.description());
            }
            updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
            updates.put("awaiting", Awaiting.NOTHING.name());
            updates.put("resolved", true);
            return updates;
        }

        switch (state.confirmation()) {
            case "YES" -> {
                updates.put("awaiting", Awaiting.NOTHING.name());
            }
            case "NO" -> {
                updates.put("toolResult", String.format(
                        "El usuario ha decidido NO cancelar el pedido #%d. La cancelación queda "
                        + "descartada.", orderId));
                updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
                updates.put("awaiting", Awaiting.NOTHING.name());
                updates.put("resolved", true);
            }
            default -> {
                updates.put("toolResult", String.format(
                        "%s. Hay que pedir al usuario que confirme la cancelación con el botón de "
                        + "confirmación (acción irreversible).", check.description()));
                updates.put("awaiting", Awaiting.CANCEL_CONFIRM.name());
                updates.put("pendingCancelId", orderId);
            }
        }
        return updates;
    }
}
