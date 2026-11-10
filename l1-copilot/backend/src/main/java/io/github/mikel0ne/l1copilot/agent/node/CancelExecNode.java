package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.Confirmation;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.dto.result.CancellationResult;
import io.github.mikel0ne.l1copilot.service.OrderService;
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

    private final OrderService orderService;

    @Override
    public Map<String, Object> apply(SupportState state) {
        Long orderId = state.orderId()
                .orElseThrow(() -> new IllegalStateException("cancel_exec without a resolved orderId"));
        log.debug("---CANCEL EXEC--- orderId={}", orderId);

        CancellationResult result = orderService.cancelOrder(orderId, state.userId());
        return Map.of(
                SupportState.TOOL_RESULT, result.reason(),
                SupportState.RESOLVED, result.cancelled(),
                SupportState.ORDER_ID, AgentState.MARK_FOR_REMOVAL,
                SupportState.CANDIDATES, AgentState.MARK_FOR_REMOVAL,
                SupportState.CONFIRMATION, Confirmation.NONE.name(),
                SupportState.AWAITING, Awaiting.NOTHING.name());
    }
}
