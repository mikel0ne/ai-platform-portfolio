package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Modo "chatty": charla informal sin relación con la tienda ("¿cómo te llamas?",
 * "¿a qué hora cierras?", bromas...). Se responde de forma breve y neutra.
 * <p>
 * Es la "paja" de la conversación: deliberadamente NO toca ningún canal de negocio
 * (foco {@code orderId}, {@code awaiting}, {@code ticketDraft}), de modo que no
 * influye en pedidos ni incidencias. Si llega en mitad de una gestión, el router
 * lo trata como digresión (igual que FAQ) y la retoma después. Se conserva en el
 * historial para que la conversación tenga sentido, pero no es una acción de negocio.
 */
@Component
@Slf4j
public class ChattyNode implements NodeAction<SupportState> {

    @Override
    public Map<String, Object> apply(SupportState state) {
        boolean digression = state.awaiting() != Awaiting.NOTHING;
        log.debug("---CHATTY--- digresión={}", digression);

        return Map.of(
                "toolResult", "El usuario ha hecho un comentario de charla informal (saludo, broma, "
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
                "resolved", !digression);
    }
}
