package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.Confirmation;
import io.github.mikel0ne.l1copilot.agent.Intent;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.UiAction;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;

@Slf4j
final class RouterShortcuts {

    private RouterShortcuts() {
    }

    static Optional<Map<String, Object>> resolve(SupportState state) {
        String message = state.userMessage();

        Optional<Long> confirmId = UiAction.orderIdOf(message, UiAction.CONFIRM_CANCEL);
        if (confirmId.isPresent()) {
            return Optional.of(confirmCommand(state, Intent.CANCEL, Awaiting.CANCEL_CONFIRM, confirmId.get()));
        }
        Optional<Long> abortId = UiAction.orderIdOf(message, UiAction.ABORT_CANCEL);
        if (abortId.isPresent()) {
            log.debug("---ROUTER--- command: dismiss cancellation #{}", abortId.get());
            return Optional.of(Map.of(
                    SupportState.INTENT, Intent.CANCEL.name(),
                    SupportState.ORDER_ID, abortId.get(),
                    SupportState.CONFIRMATION, Confirmation.NO.name(),
                    SupportState.CLARIFY_ATTEMPTS, 0));
        }

        Optional<Long> confirmReturnId = UiAction.orderIdOf(message, UiAction.CONFIRM_RETURN);
        if (confirmReturnId.isPresent()) {
            return Optional.of(confirmCommand(state, Intent.RETURN, Awaiting.RETURN_CONFIRM, confirmReturnId.get()));
        }
        Optional<Long> abortReturnId = UiAction.orderIdOf(message, UiAction.ABORT_RETURN);
        if (abortReturnId.isPresent()) {
            log.debug("---ROUTER--- command: dismiss return #{}", abortReturnId.get());
            return Optional.of(Map.of(
                    SupportState.INTENT, Intent.RETURN.name(),
                    SupportState.ORDER_ID, abortReturnId.get(),
                    SupportState.CONFIRMATION, Confirmation.NO.name(),
                    SupportState.CLARIFY_ATTEMPTS, 0));
        }

        if (state.awaiting() == Awaiting.ORDER_CHOICE) {
            Optional<Long> picked = UiAction.bareOrderId(message)
                    .filter(id -> OrderCandidates.lists(state.candidates(), id));
            if (picked.isPresent()) {
                log.debug("---ROUTER--- card selected: #{}", picked.get());
                return Optional.of(Map.of(
                        SupportState.ORDER_ID, picked.get(),
                        SupportState.AWAITING, Awaiting.NOTHING.name(),
                        SupportState.CLARIFY_ATTEMPTS, 0));
            }
        }
        return Optional.empty();
    }

    // A confirm command only counts while the agent is awaiting that confirmation for that very order;
    // a forged or stale one restarts the request, so the confirmation card is shown again.
    private static Map<String, Object> confirmCommand(SupportState state, Intent intent, Awaiting pending,
                                                      long orderId) {
        boolean expected = state.awaiting() == pending && state.orderId().filter(id -> id == orderId).isPresent();
        log.debug("---ROUTER--- command: confirm {} #{} (expected={})", intent, orderId, expected);
        return Map.of(
                SupportState.INTENT, intent.name(),
                SupportState.ORDER_ID, orderId,
                SupportState.CONFIRMATION, (expected ? Confirmation.YES : Confirmation.NONE).name(),
                SupportState.CLARIFY_ATTEMPTS, 0);
    }
}
