package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.dto.tools.CancellationCheck;
import io.github.mikel0ne.l1copilot.tools.OrderNotFoundException;
import io.github.mikel0ne.l1copilot.tools.OrderTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Skill CANCEL, paso 1: valida las reglas de negocio y gestiona el ciclo de
 * confirmación (human-in-the-loop). La cancelación solo se ejecuta
 * ({@code CancelExecNode}) si el pedido es cancelable Y el usuario confirmó.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CancelCheckNode implements NodeAction<SupportState> {

    private final OrderTools orderTools;

    @Override
    public Map<String, Object> apply(SupportState state) {
        Long orderId = state.orderId()
                .orElseThrow(() -> new IllegalStateException("cancel_check sin orderId resuelto"));
        log.debug("---CANCEL CHECK--- orderId={} confirmation={}", orderId, state.confirmation());

        Map<String, Object> updates = new HashMap<>();
        CancellationCheck check;
        try {
            check = orderTools.checkCancellable(orderId, state.userId());
        } catch (OrderNotFoundException e) {
            updates.put("toolResult", "No existe el pedido #" + orderId + " entre los pedidos del usuario.");
            updates.put("orderId", AgentState.MARK_FOR_REMOVAL);
            updates.put("cancellable", false);
            updates.put("awaiting", Awaiting.NOTHING.name());
            return updates;
        }

        updates.put("cancellable", check.cancellable());

        if (!check.cancellable()) {
            // Caso cerrado: las reglas de negocio lo impiden (lo dice Java, no el prompt)
            updates.put("toolResult", check.description());
            updates.put("awaiting", Awaiting.NOTHING.name());
            updates.put("resolved", true);
            return updates;
        }

        switch (state.confirmation()) {
            case "YES" -> {
                // La arista mandará a cancel_exec; aquí solo se limpia la pregunta pendiente
                updates.put("awaiting", Awaiting.NOTHING.name());
            }
            case "NO" -> {
                updates.put("toolResult", "El usuario ha decidido NO cancelar el pedido #" + orderId
                        + ". La cancelación queda descartada.");
                updates.put("awaiting", Awaiting.NOTHING.name());
                updates.put("resolved", true);
            }
            default -> {
                // Primera vez (o respuesta ambigua): pedir confirmación explícita
                updates.put("toolResult", check.description()
                        + ". Hay que pedir al usuario confirmación explícita (sí o no) antes de ejecutarla.");
                updates.put("awaiting", Awaiting.CANCEL_CONFIRM.name());
            }
        }
        return updates;
    }
}
