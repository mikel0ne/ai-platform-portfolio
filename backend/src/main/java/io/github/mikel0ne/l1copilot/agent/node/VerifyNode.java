package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.SupportRouting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.Intent;
import io.github.mikel0ne.l1copilot.agent.Verdict;
import io.github.mikel0ne.l1copilot.agent.llm.Verifier;
import io.github.mikel0ne.l1copilot.domain.OrderStatus;
import io.github.mikel0ne.l1copilot.dto.tools.OrderSummary;
import io.github.mikel0ne.l1copilot.tools.OrderTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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
        // El foco que se valida es el PROPUESTO este turno (aún sin comprometer) si lo hay;
        // si no, el foco vigente. Así verify juzga sobre el pedido correcto sin que el router
        // haya tenido que tocar el foco real antes de tiempo.
        long focusId = state.proposedOrderId().or(state::orderId).orElse(0L);
        Optional<OrderSummary> focus = focusId > 0
                ? orderTools.findOrder(focusId, state.userId())
                : Optional.empty();

        // Cortocircuito de negocio: una cancelación sobre un pedido que YA no es cancelable
        // (típicamente porque ya está CANCELLED, o porque ya se envió) no se debe "verificar"
        // ni preguntar "¿quieres cancelar?": no hay nada que cancelar. Se deja seguir hasta
        // cancel_check, el único que conoce las reglas de negocio, para que explique la
        // situación con claridad (ya cancelado / no se puede deshacer) y ofrezca alternativas.
        // Sin esto, verify dudaría y clarify ofrecería de nuevo cancelar, a ciegas del estado.
        if (state.intent() == Intent.CANCEL && focus.isPresent() && !isCancellable(focus.get())) {
            log.debug("---VERIFY--- CANCEL sobre pedido no cancelable (#{} {}): se omite la "
                    + "verificación y se delega en cancel_check", focusId, focus.get().status());
            Map<String, Object> updates = new HashMap<>();
            updates.put("verdict", Verdict.SURE.name());
            updates.put("tentative", false);
            commitProposedFocus(state, updates);
            return updates;
        }

        // Mismo cortocircuito para la devolución: si el pedido en foco NO admite devolución
        // (no se ha entregado, ya está en devolución, etc.) no se "verifica" ni se pregunta
        // "¿quieres devolverlo?": se delega en return_check, que conoce las reglas de negocio
        // y explicará la situación (esperar a recibirlo, devolución ya iniciada...).
        if (state.intent() == Intent.RETURN && focus.isPresent() && !isReturnable(focus.get())) {
            log.debug("---VERIFY--- RETURN sobre pedido no devolvible (#{} {}): se omite la "
                    + "verificación y se delega en return_check", focusId, focus.get().status());
            Map<String, Object> updates = new HashMap<>();
            updates.put("verdict", Verdict.SURE.name());
            updates.put("tentative", false);
            commitProposedFocus(state, updates);
            return updates;
        }

        Verifier.Decision decision = verifier.verify(
                state.userMessage(), state.intent().name(), describeFocus(focus));

        Verdict verdict = Verdict.fromLlm(decision.verdict());
        log.debug("---VERIFY--- intent={} verdict={} reason={}",
                state.intent(), verdict, decision.reason());

        Map<String, Object> updates = new HashMap<>();
        updates.put("verdict", verdict.name());
        // DOUBT que sigue adelante (una lectura) se sirve con tono tentativo: ofrecer,
        // no afirmar. En las acciones, DOUBT/REJECT van a clarify y este flag no se usa.
        updates.put("tentative", verdict == Verdict.DOUBT);

        // COMMIT diferido del foco: AHORA que la validación ha aprobado el camino, se asciende el
        // pedido propuesto a foco real y se descarta el menú. Si NO aprueba (→ clarify) no se toca
        // nada: jamás se cambia de pedido para una acción que no se va a ejecutar.
        if (SupportRouting.proceedsAfterVerify(verdict, state.intent())) {
            commitProposedFocus(state, updates);
        }
        return updates;
    }

    /** ¿El pedido en foco admite cancelación según la regla de dominio? */
    private boolean isCancellable(OrderSummary order) {
        try {
            return OrderStatus.valueOf(order.status()).isCancellable();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** ¿El pedido en foco admite devolución según la regla de dominio? */
    private boolean isReturnable(OrderSummary order) {
        try {
            return OrderStatus.valueOf(order.status()).isReturnable();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Asciende el pedido propuesto a foco real y descarta el menú (commit diferido). */
    private void commitProposedFocus(SupportState state, Map<String, Object> updates) {
        state.proposedOrderId().ifPresent(id -> {
            updates.put("orderId", id);
            updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
        });
    }

    /** Pedido en foco como dato fiable del sistema, o "(ninguno)". */
    private String describeFocus(Optional<OrderSummary> focus) {
        return focus
                .map(o -> "#" + o.id() + " '" + o.description() + "' (" + o.status() + ")")
                .orElse("(ninguno)");
    }
}
