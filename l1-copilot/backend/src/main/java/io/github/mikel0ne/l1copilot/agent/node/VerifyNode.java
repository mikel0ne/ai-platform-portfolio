package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.SupportRouting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.Intent;
import io.github.mikel0ne.l1copilot.agent.Verdict;
import io.github.mikel0ne.l1copilot.agent.llm.Verifier;
import io.github.mikel0ne.l1copilot.domain.OrderStatus;
import io.github.mikel0ne.l1copilot.dto.result.OrderSummary;
import io.github.mikel0ne.l1copilot.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class VerifyNode implements NodeAction<SupportState> {

    private final Verifier verifier;
    private final OrderService orderService;

    @Override
    public Map<String, Object> apply(SupportState state) {
        long focusId = state.proposedOrderId().or(state::orderId).orElse(0L);
        Optional<OrderSummary> focus = focusId > 0
                ? orderService.findOrder(focusId, state.userId())
                : Optional.empty();

        Verifier.Decision decision = verifier.verify(
                state.userMessage(), state.intent().name(), describeFocus(focus));

        Verdict verdict = Verdict.fromLlm(decision.verdict());
        log.debug("---VERIFY--- intent={} verdict={} reason={}",
                state.intent(), verdict, decision.reason());

        Map<String, Object> updates = new HashMap<>();
        updates.put("verdict", verdict.name());

        updates.put("tentative", verdict == Verdict.DOUBT);

        if (SupportRouting.proceedsAfterVerify(verdict, state.intent())) {
            commitProposedFocus(state, updates);
        }
        return updates;
    }

    private boolean isCancellable(OrderSummary order) {
        try {
            return OrderStatus.valueOf(order.status()).isCancellable();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isReturnable(OrderSummary order) {
        try {
            return OrderStatus.valueOf(order.status()).isReturnable();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void commitProposedFocus(SupportState state, Map<String, Object> updates) {
        state.proposedOrderId().ifPresent(id -> {
            updates.put("orderId", id);
            updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
        });
    }

    private String describeFocus(Optional<OrderSummary> focus) {
        return focus
                .map(o -> "#" + o.id() + " '" + o.description() + "' (" + o.status() + ")")
                .orElse("(ninguno)");
    }
}
