package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.Intent;
import io.github.mikel0ne.l1copilot.agent.Navigation;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.UiAction;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier;
import io.github.mikel0ne.l1copilot.config.ConversationHistoryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class RouterNode implements NodeAction<SupportState> {

    private static final Set<String> CONFIRMATIONS = Set.of("YES", "NO");

    private final IntentClassifier classifier;
    private final ConversationHistoryProperties history;

    @Override
    public Map<String, Object> apply(SupportState state) {
        log.debug("---ROUTER--- awaiting={} intent={} suspended={}",
                state.awaiting(), state.intent(), state.suspendedIntent().orElse(null));

        // UI buttons and card clicks are resolved here, so a critical action never depends on the LLM reading them right.
        Optional<Map<String, Object>> shortcut = deterministicShortcut(state);
        if (shortcut.isPresent()) {
            return shortcut.get();
        }

        // During a digression the suspended request is the current one, so resuming it keeps its intent.
        Intent effectiveCurrent = state.suspendedIntent().orElse(state.intent());

        IntentClassifier.RouterDecision decision = classifier.classify(
                state.userMessage(),
                effectiveCurrent.name(),
                state.awaiting().name(),
                describeCandidates(state),
                describeFocus(state),
                history.classifier().render(state.history()));

        Map<String, Object> updates = new HashMap<>();
        Intent intent = Intent.fromLlm(decision.intent());

        Navigation navigation = Navigation.fromLlm(decision.navigation());
        if (navigation != Navigation.NONE) {
            // A bare 'back' is resolved in code to go up exactly one level (order -> list -> menu).
            Navigation destination = resolveNavigation(navigation, state);
            log.debug("---ROUTER--- navigation {} → {}: focus released", navigation, destination);
            return navigationUpdates(destination, intent, state.intent());
        }

        if (isDigression(intent) && state.awaiting() != Awaiting.NOTHING) {
            Intent suspended = state.suspendedIntent().orElse(state.intent());
            updates.put("intent", intent.name());
            updates.put("suspendedIntent", suspended.name());
            updates.put("clarifyAttempts", 0);
            log.debug("---ROUTER--- digression {}: suspending request {} (awaiting={})",
                    intent, suspended, state.awaiting());
            return updates;
        }

        if (state.suspendedIntent().isPresent() && !isDigression(intent)) {
            updates.put("suspendedIntent", AgentState.MARK_FOR_REMOVAL);
            log.debug("---ROUTER--- digression over: resuming the request (intent={})", intent);
        }

        if (intent != Intent.NONE) {
            updates.put("intent", intent.name());
        }

        if (intent == Intent.SUPPORT) {
            updates.put("orderRelated", decision.orderRelated());
        }

        boolean abandoningTicketGate = (state.awaiting() == Awaiting.TICKET_CONFIRM
                || state.awaiting() == Awaiting.TICKET_INFO)
                && intent != Intent.SUPPORT && intent != Intent.NONE;
        if (abandoningTicketGate) {
            updates.put("ticketDraft", AgentState.MARK_FOR_REMOVAL);
            updates.put("awaiting", Awaiting.NOTHING.name());
            log.debug("---ROUTER--- ticket dropped: the user asks for something else (intent={})", intent);
        }

        if (decision.orderId() != null && decision.orderId() > 0) {
            long newFocus = decision.orderId();
            boolean selecting = state.awaiting() == Awaiting.ORDER_CHOICE;
            boolean differentOrder = state.orderId().filter(id -> id == newFocus).isEmpty();
            if (selecting) {
                updates.put("awaiting", Awaiting.NOTHING.name());
            }

            if (routesToVerify(intent, updates, state)) {
                updates.put("proposedOrderId", newFocus);
            } else {
                updates.put("orderId", newFocus);
                if (!selecting && differentOrder) {
                    updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
                }
            }
        }

        if (state.awaiting() == Awaiting.CANCEL_CONFIRM || state.awaiting() == Awaiting.RETURN_CONFIRM
                || state.awaiting() == Awaiting.TICKET_CONFIRM) {
            String confirmation = decision.confirmation() == null
                    ? "NONE" : decision.confirmation().trim().toUpperCase();
            updates.put("confirmation", CONFIRMATIONS.contains(confirmation) ? confirmation : "NONE");
        }

        if (intent == Intent.OTHER && hasOngoingContext(state) && !abandoningTicketGate) {
            updates.put("clarifyAttempts", state.clarifyAttempts() + 1);
        } else {
            updates.put("clarifyAttempts", 0);
        }

        log.debug("---ROUTER--- decision: intent={} orderId={} confirmation={}",
                decision.intent(), decision.orderId(), decision.confirmation());
        return updates;
    }

    private Optional<Map<String, Object>> deterministicShortcut(SupportState state) {
        String message = state.userMessage();

        Optional<Long> confirmId = UiAction.orderIdOf(message, UiAction.CONFIRM_CANCEL);
        if (confirmId.isPresent()) {
            return Optional.of(confirmCommand(state, Intent.CANCEL, Awaiting.CANCEL_CONFIRM, confirmId.get()));
        }
        Optional<Long> abortId = UiAction.orderIdOf(message, UiAction.ABORT_CANCEL);
        if (abortId.isPresent()) {
            log.debug("---ROUTER--- command: dismiss cancellation #{}", abortId.get());
            return Optional.of(Map.of(
                    "intent", Intent.CANCEL.name(),
                    "orderId", abortId.get(),
                    "confirmation", "NO",
                    "clarifyAttempts", 0));
        }

        Optional<Long> confirmReturnId = UiAction.orderIdOf(message, UiAction.CONFIRM_RETURN);
        if (confirmReturnId.isPresent()) {
            return Optional.of(confirmCommand(state, Intent.RETURN, Awaiting.RETURN_CONFIRM, confirmReturnId.get()));
        }
        Optional<Long> abortReturnId = UiAction.orderIdOf(message, UiAction.ABORT_RETURN);
        if (abortReturnId.isPresent()) {
            log.debug("---ROUTER--- command: dismiss return #{}", abortReturnId.get());
            return Optional.of(Map.of(
                    "intent", Intent.RETURN.name(),
                    "orderId", abortReturnId.get(),
                    "confirmation", "NO",
                    "clarifyAttempts", 0));
        }

        if (state.awaiting() == Awaiting.ORDER_CHOICE) {
            Optional<Long> picked = UiAction.bareOrderId(message).filter(id -> isCandidate(state, id));
            if (picked.isPresent()) {
                log.debug("---ROUTER--- card selected: #{}", picked.get());
                return Optional.of(Map.of(
                        "orderId", picked.get(),
                        "awaiting", Awaiting.NOTHING.name(),
                        "clarifyAttempts", 0));
            }
        }
        return Optional.empty();
    }

    // A confirm command only counts while the agent is awaiting that confirmation for that very order;
    // a forged or stale one restarts the request, so the confirmation card is shown again.
    private Map<String, Object> confirmCommand(SupportState state, Intent intent, Awaiting pending, long orderId) {
        boolean expected = state.awaiting() == pending && state.orderId().filter(id -> id == orderId).isPresent();
        log.debug("---ROUTER--- command: confirm {} #{} (expected={})", intent, orderId, expected);
        return Map.of(
                "intent", intent.name(),
                "orderId", orderId,
                "confirmation", expected ? "YES" : "NONE",
                "clarifyAttempts", 0);
    }

    private boolean isCandidate(SupportState state, long orderId) {
        String prefix = "#" + orderId + " ";
        return state.candidates().stream().anyMatch(c -> c.startsWith(prefix));
    }

    private boolean routesToVerify(Intent intent, Map<String, Object> updates, SupportState state) {
        boolean verifiable = intent == Intent.TRACKING || intent == Intent.CANCEL
                || intent == Intent.RETURN;
        return verifiable && effectiveAwaiting(updates, state) == Awaiting.NOTHING && effectiveTicketBlank(updates, state);
    }

    private Awaiting effectiveAwaiting(Map<String, Object> updates, SupportState state) {
        Object pending = updates.get("awaiting");
        return pending instanceof String s ? Awaiting.fromState(s) : state.awaiting();
    }

    private boolean effectiveTicketBlank(Map<String, Object> updates, SupportState state) {
        return updates.get("ticketDraft") == AgentState.MARK_FOR_REMOVAL || state.ticketDraft().isBlank();
    }

    private boolean hasOngoingContext(SupportState state) {
        return state.orderId().isPresent() || state.awaiting() != Awaiting.NOTHING;
    }

    private boolean isDigression(Intent intent) {
        return intent == Intent.FAQ || intent == Intent.CHATTY;
    }

    private Navigation resolveNavigation(Navigation navigation, SupportState state) {
        if (navigation != Navigation.BACK) {
            return navigation;
        }
        boolean belowListing = state.orderId().isPresent()
                || (!state.candidates().isEmpty() && state.awaiting() != Awaiting.ORDER_CHOICE);
        return belowListing ? Navigation.BACK_TO_LIST : Navigation.BACK_TO_MENU;
    }

    private Intent targetForBackToList(Intent turnIntent, Intent currentIntent) {
        if (turnIntent == Intent.TRACKING || turnIntent == Intent.CANCEL || turnIntent == Intent.RETURN) {
            return turnIntent;
        }
        if (currentIntent == Intent.CANCEL || currentIntent == Intent.RETURN) {
            return currentIntent;
        }
        return Intent.TRACKING;
    }

    private Map<String, Object> navigationUpdates(Navigation navigation, Intent turnIntent, Intent currentIntent) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("orderId", AgentState.MARK_FOR_REMOVAL);
        updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
        updates.put("ticketDraft", AgentState.MARK_FOR_REMOVAL);
        updates.put("suspendedIntent", AgentState.MARK_FOR_REMOVAL);
        updates.put("awaiting", Awaiting.NOTHING.name());
        updates.put("clarifyAttempts", 0);

        updates.put("navigating", true);

        Intent target = navigation == Navigation.BACK_TO_MENU
                ? Intent.OTHER
                : targetForBackToList(turnIntent, currentIntent);
        updates.put("intent", target.name());
        return updates;
    }

    private String describeCandidates(SupportState state) {
        if (state.candidates().isEmpty()) {
            return "(ninguno)";
        }
        return String.join("; ", state.candidates());
    }

    private String describeFocus(SupportState state) {
        return state.orderId().map(id -> "#" + id).orElse("(ninguno)");
    }
}
