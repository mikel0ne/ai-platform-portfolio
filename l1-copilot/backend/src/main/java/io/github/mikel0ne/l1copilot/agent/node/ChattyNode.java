package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
public class ChattyNode implements NodeAction<SupportState> {

    @Override
    public Map<String, Object> apply(SupportState state) {
        boolean digression = state.awaiting() != Awaiting.NOTHING;
        log.debug("---CHATTY--- digression={}", digression);

        return Map.of(
                SupportState.TOOL_RESULT, "El usuario ha hecho un comentario de charla informal (saludo, broma, "
                        + "pregunta personal...), NO una gestión de la tienda. PRIORIDAD: responder "
                        + "de verdad a lo que ha dicho y seguir el hilo de la conversación, breve, "
                        + "cordial y natural, como en una conversación normal. NO inventes datos "
                        + "personales. No te empeñes en reconducir hacia la tienda ni remates por "
                        + "sistema con coletillas tipo '¿te ayudo con tus pedidos?': si encaja de "
                        + "forma natural mencionar algo de la tienda, mételo; si no encaja, no lo "
                        + "metas. Mantén siempre un tono profesional, educado y respetuoso: nada de "
                        + "insultos ni de opinar o debatir sobre temas polémicos (política, "
                        + "religión, sexo...); si el usuario los saca, declínalo con tacto y "
                        + "reconduce con amabilidad. No afecta a ninguna gestión.",
                SupportState.RESOLVED, !digression);
    }
}
