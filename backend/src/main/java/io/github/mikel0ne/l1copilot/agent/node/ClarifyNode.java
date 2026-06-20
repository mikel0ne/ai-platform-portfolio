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

/**
 * Nodo clarify: se llega cuando {@code verify} no está seguro de la acción que
 * eligió el router. Atiende dudas sobre CANCEL y RETURN (SUPPORT no pasa por
 * verify/clarify: su salvaguarda es el gate de confirmación del propio nodo
 * support). No ejecuta NADA ni deja nada pendiente; prepara para {@code respond}
 * una pregunta aclaratoria enfocada. La valla (qué opciones existen) la pone el
 * código; el peso y la redacción (cuáles ofrecer y cómo, ancladas al pedido en
 * foco) los hace el {@code Responder} dentro de esa valla.
 */
@Component
@Slf4j
public class ClarifyNode implements NodeAction<SupportState> {

    @Override
    public Map<String, Object> apply(SupportState state) {
        Intent suspected = state.intent();
        Verdict verdict = state.verdict();
        // DOUBT: la gestión es plausible → se ofrece PRIMERO. REJECT: probablemente no era
        // eso → se lidera con alternativas y la gestión sospechada se deja para el final.
        boolean lead = verdict == Verdict.DOUBT;
        log.debug("---CLARIFY--- sospecha={} veredicto={} (lidera la sospecha={})", suspected, verdict, lead);

        String guess = switch (suspected) {
            case CANCEL -> "cancelar un pedido";
            case RETURN -> "iniciar la devolución de un pedido";
            default -> "esa gestión";
        };

        // El prefijo CLARIFY: lo reconoce el Responder para aplicar la regla de aclaración
        // (pregunta enfocada con opciones ponderadas) por encima de las reglas de AWAITING.
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
        // clarify solo atiende dudas sobre CANCEL/RETURN; no deja nada pendiente. (SUPPORT no pasa
        // por verify/clarify: su propio gate de confirmación es la salvaguarda.)
        updates.put("awaiting", Awaiting.NOTHING.name());
        return updates;
    }
}
