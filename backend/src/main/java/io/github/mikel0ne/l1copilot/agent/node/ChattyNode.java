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
                "toolResult", "El usuario ha hecho un comentario de charla informal, no una "
                        + "gestión de la tienda. Responder de forma breve, cordial y natural, sin "
                        + "inventar datos personales ni listar capacidades salvo que lo pida, y sin "
                        + "cambiar de tema. No afecta a ninguna gestión.",
                "resolved", !digression);
    }
}
