package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.dto.tools.CancellationResult;
import io.github.mikel0ne.l1copilot.tools.OrderTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class CancelExecNode implements NodeAction<SupportState> {

    private final OrderTools orderTools;

    @Override
    public Map<String, Object> apply(SupportState state) {
        Long orderId = state.orderId()
                .orElseThrow(() -> new IllegalStateException("cancel_exec without a resolved orderId"));
        log.debug("---CANCEL EXEC--- orderId={}", orderId);

        CancellationResult result = orderTools.cancelOrder(orderId, state.userId());
        return Map.of(
                "toolResult", result.reason(),
                "resolved", result.cancelled(),
                "orderId", AgentState.MARK_FOR_REMOVAL,
                "candidates", AgentState.MARK_FOR_REMOVAL,
                "confirmation", "NONE",
                "awaiting", Awaiting.NOTHING.name());
    }
}
