package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.dto.tools.CancellationResult;
import io.github.mikel0ne.l1copilot.tools.OrderTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Skill CANCEL, paso 2: ejecuta la cancelación. Solo se llega aquí tras la
 * confirmación del usuario; la tool revalida igualmente las reglas (el estado
 * del pedido pudo cambiar entre turnos).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CancelExecNode implements NodeAction<SupportState> {

    private final OrderTools orderTools;

    @Override
    public Map<String, Object> apply(SupportState state) {
        Long orderId = state.orderId()
                .orElseThrow(() -> new IllegalStateException("cancel_exec sin orderId resuelto"));
        log.debug("---CANCEL EXEC--- orderId={}", orderId);

        CancellationResult result = orderTools.cancelOrder(orderId, state.userId());
        return Map.of(
                "toolResult", result.reason(),
                "resolved", result.cancelled(),
                "confirmation", "NONE",
                "awaiting", Awaiting.NOTHING.name());
    }
}
