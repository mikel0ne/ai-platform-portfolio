package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.Intent;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Nodo router: clasifica la intención del mensaje, extrae el pedido referido
 * (incluida la resolución de referencias sobre los candidatos ya ofrecidos:
 * "el portátil" → #123) y detecta confirmaciones pendientes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RouterNode implements NodeAction<SupportState> {

    private static final Set<String> CONFIRMATIONS = Set.of("YES", "NO");

    private final IntentClassifier classifier;

    @Override
    public Map<String, Object> apply(SupportState state) {
        log.debug("---ROUTER--- awaiting={} intent={}", state.awaiting(), state.intent());

        IntentClassifier.RouterDecision decision = classifier.classify(
                state.userMessage(),
                state.intent().name(),
                state.awaiting().name(),
                describeCandidates(state));

        Map<String, Object> updates = new HashMap<>();

        Intent intent = Intent.fromLlm(decision.intent());
        if (intent != Intent.NONE) {
            updates.put("intent", intent.name());
        }

        if (decision.orderId() != null && decision.orderId() > 0) {
            updates.put("orderId", decision.orderId());
            if (state.awaiting() == Awaiting.ORDER_CHOICE) {
                updates.put("awaiting", Awaiting.NOTHING.name());
            }
        }

        // La confirmación solo se honra si realmente había una pendiente:
        // un "sí" suelto en el primer turno jamás dispara una cancelación.
        if (state.awaiting() == Awaiting.CANCEL_CONFIRM) {
            String confirmation = decision.confirmation() == null
                    ? "NONE" : decision.confirmation().trim().toUpperCase();
            updates.put("confirmation", CONFIRMATIONS.contains(confirmation) ? confirmation : "NONE");
        }

        log.debug("---ROUTER--- decision: intent={} orderId={} confirmation={}",
                decision.intent(), decision.orderId(), decision.confirmation());
        return updates;
    }

    private String describeCandidates(SupportState state) {
        if (state.candidates().isEmpty()) {
            return "(ninguno)";
        }
        StringBuilder sb = new StringBuilder();
        state.candidates().forEach(c -> sb.append("#").append(c.id()).append(" ")
                .append(c.description()).append(" (").append(c.status()).append("); "));
        return sb.toString();
    }
}
