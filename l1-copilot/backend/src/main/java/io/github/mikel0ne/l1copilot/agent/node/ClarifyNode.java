package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.Intent;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.Verdict;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class ClarifyNode implements NodeAction<SupportState> {

    @Override
    public Map<String, Object> apply(SupportState state) {
        Intent suspected = state.intent();
        Verdict verdict = state.verdict();

        boolean lead = verdict == Verdict.DOUBT;
        log.debug("---CLARIFY--- suspected={} verdict={} (lead with suspicion={})", suspected, verdict, lead);

        String guess = switch (suspected) {
            case CANCEL -> "cancelar un pedido";
            case RETURN -> "iniciar la devolución de un pedido";
            default -> "esa gestión";
        };

        String instruction = lead
                ? "CLARIFY: el sistema cree que el usuario quería " + guess + ", pero no está "
                  + "seguro y no se ha ejecutado nada. Pregunta de forma breve y enfocada, "
                  + "ofreciendo PRIMERO esa gestión (anclada al pedido en foco si lo hay) y, si "
                  + "encaja, una respuesta informativa o atender un comentario como alternativa."
                : "CLARIFY: el sistema NO cree que el usuario quisiera " + guess + " y no se ha "
                  + "ejecutado nada. Pregunta qué quiere en realidad, ofreciendo PRIMERO las "
                  + "alternativas más probables (información general, o atender su comentario con "
                  + "naturalidad) y mencionando " + guess + " solo si acaso, al final.";

        Map<String, Object> updates = new HashMap<>();
        updates.put("toolResult", instruction);

        updates.put("awaiting", Awaiting.NOTHING.name());
        return updates;
    }
}
