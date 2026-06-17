package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.llm.FaqKnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Skill FAQ (intención FAQ): preguntas generales sobre políticas de la tienda
 * (envíos, devoluciones, pagos, garantía...). No toca base de datos ni LLM
 * propio; entrega la base de conocimiento a respond, que redacta la respuesta
 * citando solo lo relevante.
 * <p>
 * Puede atenderse como <em>digresión</em>: si llega en medio de una gestión con
 * algo pendiente ({@code awaiting != NOTHING}), no se cierra nada ni se toca el
 * contexto de la tarea —el router ya guardó {@code suspendedIntent}—; respond
 * responde la consulta y recuerda la gestión pendiente para retomarla.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FaqNode implements NodeAction<SupportState> {

    private final FaqKnowledgeBase knowledgeBase;

    @Override
    public Map<String, Object> apply(SupportState state) {
        boolean digression = state.awaiting() != Awaiting.NOTHING;
        log.debug("---FAQ--- digresión={}", digression);

        // En una digresión la gestión de fondo sigue abierta (resolved=false) y no se
        // toca awaiting: el contexto de la tarea queda intacto para retomarlo.
        return Map.of(
                "toolResult", "Base de conocimiento de la tienda (usa solo lo "
                        + "relevante para la pregunta del usuario):\n" + knowledgeBase.content(),
                "resolved", !digression);
    }
}
