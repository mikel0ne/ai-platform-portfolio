package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.Verdict;
import io.github.mikel0ne.l1copilot.agent.llm.Verifier;
import io.github.mikel0ne.l1copilot.tools.OrderTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Nodo verify: segunda opinión antes de ejecutar una acción (CANCEL/SUPPORT).
 * Pregunta al {@link Verifier} si la acción que eligió el router es claramente
 * lo que el usuario pide y deja el veredicto en {@code verifyOk}. La arista
 * {@code SupportRouting.fromVerify} decide: SEGURO sigue a la acción, DUDA va a
 * {@code clarify} para preguntar en vez de ejecutar.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VerifyNode implements NodeAction<SupportState> {

    private final Verifier verifier;
    private final OrderTools orderTools;

    @Override
    public Map<String, Object> apply(SupportState state) {
        Verifier.Decision decision = verifier.verify(
                state.userMessage(), state.intent().name(), describeFocus(state));

        Verdict verdict = Verdict.fromLlm(decision.verdict());
        log.debug("---VERIFY--- intent={} verdict={} reason={}",
                state.intent(), verdict, decision.reason());

        // DOUBT que sigue adelante (una lectura) se sirve con tono tentativo: ofrecer,
        // no afirmar. En las acciones, DOUBT/REJECT van a clarify y este flag no se usa.
        return Map.of(
                "verdict", verdict.name(),
                "tentative", verdict == Verdict.DOUBT);
    }

    /** Pedido en foco como dato fiable del sistema, o "(ninguno)". */
    private String describeFocus(SupportState state) {
        return state.orderId()
                .flatMap(id -> orderTools.findOrder(id, state.userId()))
                .map(o -> "#" + o.id() + " '" + o.description() + "' (" + o.status() + ")")
                .orElse("(ninguno)");
    }
}
