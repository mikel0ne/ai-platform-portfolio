package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Camino por defecto (intención OTHER). Tiene dos caras según el contador de
 * aclaración que fija el router:
 * <ul>
 *   <li>sin gestión en curso → presentación de capacidades (smalltalk/onboarding);</li>
 *   <li>con una gestión en curso (mensaje ambiguo) → modo aclaración: en vez de
 *   volcar el menú, se pide al usuario que concrete sobre el pedido en foco, y si
 *   se repite ({@code clarifyAttempts >= 2}) se sugiere volver al menú principal.</li>
 * </ul>
 * No toca base de datos ni LLM; la redacción la hace respond.
 */
@Component
@Slf4j
public class FallbackNode implements NodeAction<SupportState> {

    private static final String CAPABILITIES = "Mensaje fuera de las gestiones soportadas. "
            + "Saludar si procede y explicar las capacidades: informar del estado de envíos, "
            + "cancelar pedidos, abrir incidencias para un agente humano, consultar las "
            + "incidencias ya abiertas y su estado, y responder dudas generales sobre envíos, "
            + "devoluciones, pagos o garantía. Preguntar además si el usuario tiene alguna duda "
            + "general de ese tipo. No ofrecer NUNCA tareas fuera de esas capacidades.";

    private static final String CLARIFY = "El usuario ha dicho algo poco claro mientras "
            + "seguíamos con el pedido en foco (ver PEDIDO_EN_FOCO). NO listes todas las "
            + "capacidades en frío: pregunta con tacto, tomando ese pedido como contexto, qué "
            + "quiere hacer con él (consultar el envío, cancelarlo, o abrir una incidencia) o si "
            + "tiene alguna duda general.";

    private static final String CLARIFY_ESCALATE = "El usuario vuelve a decir algo poco claro "
            + "sobre el pedido en foco (ver PEDIDO_EN_FOCO). Reorienta con tacto: recuerda en una "
            + "frase qué se puede hacer con ese pedido (envío, cancelación, incidencia) o "
            + "responder una duda general, y sugiérele volver al menú principal si prefiere "
            + "empezar de cero.";

    @Override
    public Map<String, Object> apply(SupportState state) {
        int attempts = state.clarifyAttempts();
        log.debug("---FALLBACK--- clarifyAttempts={}", attempts);

        String toolResult = attempts >= 2 ? CLARIFY_ESCALATE
                : attempts == 1 ? CLARIFY
                : CAPABILITIES;

        return Map.of(
                "toolResult", toolResult,
                "awaiting", Awaiting.NOTHING.name());
    }
}
