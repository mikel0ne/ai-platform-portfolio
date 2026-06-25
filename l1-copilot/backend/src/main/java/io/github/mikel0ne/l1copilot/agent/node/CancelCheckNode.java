package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.domain.OrderStatus;
import io.github.mikel0ne.l1copilot.dto.tools.CancellationCheck;
import io.github.mikel0ne.l1copilot.tools.OrderNotFoundException;
import io.github.mikel0ne.l1copilot.tools.OrderTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Skill CANCEL, paso 1: valida las reglas de negocio y gestiona el ciclo de
 * confirmación (human-in-the-loop). La cancelación solo se ejecuta
 * ({@code CancelExecNode}) si el pedido es cancelable Y el usuario confirmó.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CancelCheckNode implements NodeAction<SupportState> {

    private final OrderTools orderTools;

    @Override
    public Map<String, Object> apply(SupportState state) {
        Long orderId = state.orderId()
                .orElseThrow(() -> new IllegalStateException("cancel_check sin orderId resuelto"));
        log.debug("---CANCEL CHECK--- orderId={} confirmation={}", orderId, state.confirmation());

        Map<String, Object> updates = new HashMap<>();
        CancellationCheck check;
        try {
            check = orderTools.checkCancellable(orderId, state.userId());
        } catch (OrderNotFoundException e) {
            updates.put("toolResult", String.format(
                    "No existe el pedido #%d entre los pedidos del usuario.", orderId));
            updates.put("orderId", AgentState.MARK_FOR_REMOVAL);
            updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
            updates.put("cancellable", false);
            updates.put("awaiting", Awaiting.NOTHING.name());
            return updates;
        }

        updates.put("cancellable", check.cancellable());

        if (!check.cancellable()) {
            // Las reglas de negocio lo impiden (lo dice Java, no el prompt). Se cierra el
            // intento de cancelación pero NO se suelta el foco: el usuario sigue "sobre" ese
            // pedido y puede preguntar otras cosas (alternativas, dudas) sin volver a elegirlo.
            //
            // Caso especial: el pedido YA está cancelado. No es un "no se puede cancelar"
            // cualquiera (como SHIPPED/DELIVERED): hay que decirle al usuario, sin rodeos,
            // que ya está cancelado y que es irreversible, y reconducir hacia las gestiones
            // que SÍ son posibles sobre un pedido cancelado (p. ej. abrir una incidencia si
            // no le ha llegado el reembolso). Se da una instrucción explícita al responder.
            boolean alreadyCancelled = OrderStatus.CANCELLED.name().equals(check.status());
            if (alreadyCancelled) {
                updates.put("toolResult", String.format(
                        "Hechos sobre el pedido #%d que hay que transmitir al cliente: ya está "
                        + "cancelado; la cancelación es una acción irreversible que no puede "
                        + "repetirse, así que no procede volver a cancelarlo. El cliente puede "
                        + "necesitar información (por ejemplo sobre el reembolso o las políticas de "
                        + "cancelación) o tener algún problema con esa cancelación; quédate a su "
                        + "disposición para ayudarle con ello, sin dar por hecho que quiere abrir "
                        + "una incidencia ni ofrecérsela salvo que la pida.", orderId));
            } else if (OrderStatus.DELIVERED.name().equals(check.status())) {
                // Entregado: cancelar ya no aplica; la vía correcta es una DEVOLUCIÓN.
                updates.put("toolResult", String.format(
                        "Hechos sobre el pedido #%d que hay que transmitir al cliente: ya está "
                        + "ENTREGADO, por lo que no es posible cancelarlo. Lo que procede en su lugar "
                        + "es una DEVOLUCIÓN: explícaselo y ofrécete a iniciar la devolución de este "
                        + "pedido (no una incidencia). No prometas plazos ni inventes datos.", orderId));
            } else {
                updates.put("toolResult", check.description());
            }
            updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
            updates.put("awaiting", Awaiting.NOTHING.name());
            updates.put("resolved", true);
            return updates;
        }

        switch (state.confirmation()) {
            case "YES" -> {
                // La arista mandará a cancel_exec; aquí solo se limpia la pregunta pendiente
                updates.put("awaiting", Awaiting.NOTHING.name());
            }
            case "NO" -> {
                // El usuario descarta la cancelación pero NO se suelta el foco: el pedido sigue
                // vivo y acaba de afirmar interés explícito ("conservarlo"), así que sigue "sobre"
                // él y puede preguntar otras cosas (estado, dudas, devolución) sin re-elegirlo.
                // Mismo criterio que el caso "no cancelable" de arriba. (Soltar el foco solo es
                // obligado cuando la cancelación SÍ se ejecuta, en CancelExecNode.)
                updates.put("toolResult", String.format(
                        "El usuario ha decidido NO cancelar el pedido #%d. La cancelación queda "
                        + "descartada.", orderId));
                updates.put("candidates", AgentState.MARK_FOR_REMOVAL);
                updates.put("awaiting", Awaiting.NOTHING.name());
                updates.put("resolved", true);
            }
            default -> {
                // Primera vez (o respuesta ambigua): pedir confirmación explícita. Se emite
                // además la acción de confirmación para que la UI muestre el botón: el clic
                // será una autorización inequívoca, no texto libre que el modelo deba interpretar.
                updates.put("toolResult", String.format(
                        "%s. Hay que pedir al usuario que confirme la cancelación con el botón de "
                        + "confirmación (acción irreversible).", check.description()));
                updates.put("awaiting", Awaiting.CANCEL_CONFIRM.name());
                updates.put("pendingCancelId", orderId);
            }
        }
        return updates;
    }
}
