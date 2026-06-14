package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.dto.tools.OrderSummary;
import io.github.mikel0ne.l1copilot.tools.OrderTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Subflujo reutilizable de desambiguación (lo comparten TRACKING y CANCEL).
 * En vez de pedir el id a ciegas, busca proactivamente los pedidos recientes
 * del usuario: si hay uno solo lo autoselecciona; si hay varios, los ofrece
 * y el turno terminará preguntando.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResolveOrderNode implements NodeAction<SupportState> {

    private final OrderTools orderTools;

    @Override
    public Map<String, Object> apply(SupportState state) {
        log.debug("---RESOLVE ORDER---");
        List<OrderSummary> recent = orderTools.getRecentOrders(state.userId());

        if (recent.isEmpty()) {
            return Map.of(
                    "toolResult", "El usuario no tiene ningún pedido registrado.",
                    "awaiting", Awaiting.NOTHING.name());
        }

        if (recent.size() == 1) {
            log.debug("---RESOLVE ORDER--- único candidato: {}", recent.get(0).id());
            return Map.of(
                    "orderId", recent.get(0).id(),
                    "candidates", recent,
                    "awaiting", Awaiting.NOTHING.name());
        }

        String listing = recent.stream()
                .map(o -> "#" + o.id() + " " + o.description() + " (pedido el " + o.orderDate() + ")")
                .collect(Collectors.joining("; "));
        log.debug("---RESOLVE ORDER--- {} candidatos, se pide elección", recent.size());
        return Map.of(
                "candidates", recent,
                "awaiting", Awaiting.ORDER_CHOICE.name(),
                "toolResult", "El usuario tiene varios pedidos recientes: " + listing
                        + ". Hay que preguntarle sobre cuál quiere actuar.");
    }
}
