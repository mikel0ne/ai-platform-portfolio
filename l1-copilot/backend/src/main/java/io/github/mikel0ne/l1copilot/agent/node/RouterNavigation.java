package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.Intent;
import io.github.mikel0ne.l1copilot.agent.Navigation;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.state.AgentState;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
final class RouterNavigation {

    private RouterNavigation() {
    }

    static Optional<Map<String, Object>> resolve(Navigation requested, Intent turnIntent, SupportState state) {
        if (requested == Navigation.NONE) {
            return Optional.empty();
        }
        Navigation destination = destination(requested, state);
        log.debug("---ROUTER--- navigation {} → {}: focus released", requested, destination);
        return Optional.of(updates(destination, turnIntent, state.intent()));
    }

    // A bare 'back' is resolved in code to go up exactly one level (order -> list -> menu).
    static Navigation destination(Navigation navigation, SupportState state) {
        if (navigation != Navigation.BACK) {
            return navigation;
        }
        boolean belowListing = state.orderId().isPresent()
                || (!state.candidates().isEmpty() && state.awaiting() != Awaiting.ORDER_CHOICE);
        return belowListing ? Navigation.BACK_TO_LIST : Navigation.BACK_TO_MENU;
    }

    static Intent targetForBackToList(Intent turnIntent, Intent currentIntent) {
        if (turnIntent == Intent.TRACKING || turnIntent == Intent.CANCEL || turnIntent == Intent.RETURN) {
            return turnIntent;
        }
        if (currentIntent == Intent.CANCEL || currentIntent == Intent.RETURN) {
            return currentIntent;
        }
        return Intent.TRACKING;
    }

    static Map<String, Object> updates(Navigation navigation, Intent turnIntent, Intent currentIntent) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(SupportState.ORDER_ID, AgentState.MARK_FOR_REMOVAL);
        updates.put(SupportState.CANDIDATES, AgentState.MARK_FOR_REMOVAL);
        updates.put(SupportState.TICKET_DRAFT, AgentState.MARK_FOR_REMOVAL);
        updates.put(SupportState.SUSPENDED_INTENT, AgentState.MARK_FOR_REMOVAL);
        updates.put(SupportState.AWAITING, Awaiting.NOTHING.name());
        updates.put(SupportState.CLARIFY_ATTEMPTS, 0);

        updates.put(SupportState.NAVIGATING, true);

        Intent target = navigation == Navigation.BACK_TO_MENU
                ? Intent.OTHER
                : targetForBackToList(turnIntent, currentIntent);
        updates.put(SupportState.INTENT, target.name());
        return updates;
    }
}
