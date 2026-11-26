package io.github.mikel0ne.l1copilot.agent.node;

import dev.langchain4j.service.output.OutputParsingException;
import io.github.mikel0ne.l1copilot.agent.AssentGuard;
import io.github.mikel0ne.l1copilot.agent.Confirmation;
import io.github.mikel0ne.l1copilot.agent.Intent;
import io.github.mikel0ne.l1copilot.agent.Navigation;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier;
import io.github.mikel0ne.l1copilot.agent.llm.PromptData;
import io.github.mikel0ne.l1copilot.config.ConversationHistoryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class RouterNode implements NodeAction<SupportState> {

    private final IntentClassifier classifier;
    private final ConversationHistoryProperties history;

    @Override
    public Map<String, Object> apply(SupportState state) {
        Map<String, Object> updates = new HashMap<>(route(state));
        boolean offerAccepted = state.offeredIntent()
                .filter(offered -> offered.name().equals(updates.get(SupportState.INTENT)))
                .isPresent();
        if (state.offeredIntent().isPresent() && !offerAccepted) {
            updates.put(SupportState.OFFERED_INTENT, Intent.NONE.name());
        }
        return updates;
    }

    private Map<String, Object> route(SupportState state) {
        log.debug("---ROUTER--- awaiting={} intent={} suspended={}",
                state.awaiting(), state.intent(), state.suspendedIntent().orElse(null));

        // UI buttons and card clicks are resolved here, so a critical action never depends on the LLM reading them right.
        Optional<Map<String, Object>> shortcut = RouterShortcuts.resolve(state);
        if (shortcut.isPresent()) {
            return shortcut.get();
        }

        // During a digression the suspended request is the current one, so resuming it keeps its intent.
        Intent effectiveCurrent = state.suspendedIntent().orElse(state.intent());

        IntentClassifier.RouterDecision raw = classify(state, effectiveCurrent);
        IntentClassifier.RouterDecision decision =
                AssentGuard.apply(raw, state.awaiting(), state.offeredIntent(), state.userMessage());
        if (!decision.equals(raw)) {
            log.debug("---ROUTER--- bare assent with nothing awaited: {} -> {} (offered={})",
                    raw.intent(), decision.intent(), state.offeredIntent().orElse(null));
        }

        Optional<Map<String, Object>> navigation = RouterNavigation.resolve(
                Navigation.fromLlm(decision.navigation()), Intent.fromLlm(decision.intent()), state);
        if (navigation.isPresent()) {
            return navigation.get();
        }

        RouterTurn turn = new RouterTurn(state, decision);
        if (RouterPolicies.suspendForDigression(turn)) {
            return turn.updates();
        }

        // Draft drop goes before focus because routesToVerify reads the effective awaiting and draft; focus
        // goes before confirmation because confirmsPendingAction reads the order the focus step wrote.
        RouterPolicies.resumeAfterDigression(turn);
        RouterPolicies.recordIntent(turn);
        RouterPolicies.dropTicketDraft(turn);
        RouterPolicies.moveFocus(turn);
        RouterPolicies.bindConfirmation(turn);
        RouterPolicies.countClarifyAttempts(turn);

        log.debug("---ROUTER--- decision: intent={} orderId={} confirmation={}",
                decision.intent(), decision.orderId(), decision.confirmation());
        return turn.updates();
    }

    // An unparseable decision degrades to OTHER, which asks for clarification and executes nothing.
    private IntentClassifier.RouterDecision classify(SupportState state, Intent effectiveCurrent) {
        try {
            return classifier.classify(
                    state.userMessage(),
                    effectiveCurrent.name(),
                    state.awaiting().name(),
                    describeCandidates(state),
                    describeFocus(state),
                    IntentClassifier.fenceSafe(history.classifier().render(state.history())));
        } catch (OutputParsingException e) {
            log.warn("Router output not parseable, degrading to OTHER: {}", e.getMessage());
            return new IntentClassifier.RouterDecision(Intent.OTHER.name(), null, Confirmation.NONE.name(),
                    Navigation.NONE.name());
        }
    }

    private String describeCandidates(SupportState state) {
        if (state.candidates().isEmpty()) {
            return "(ninguno)";
        }
        return PromptData.strings(state.candidates());
    }

    private String describeFocus(SupportState state) {
        return state.orderId().map(id -> "#" + id).orElse("(ninguno)");
    }
}
