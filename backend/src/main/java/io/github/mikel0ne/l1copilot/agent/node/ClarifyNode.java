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
 * eligió el router. No ejecuta NADA; prepara para {@code respond} una pregunta
 * aclaratoria enfocada. La valla (qué opciones existen) la pone el código; el
 * peso y la redacción (cuáles ofrecer y cómo, ancladas al pedido en foco) los
 * hace el {@code Responder} dentro de esa valla.
 * <p>
 * Para no preguntar dos veces por una incidencia: si lo sospechado era SUPPORT,
 * se siembra el borrador y se deja {@code awaiting=TICKET_CONFIRM}. Así, si el
 * usuario confirma, el siguiente turno crea el ticket directamente (el router no
 * vuelve a verificar y el nodo support ya está "en el gate"), y si cambia de idea
 * la lógica de abandono del router descarta el borrador. La cancelación, en
 * cambio, NO se pre-arma: conserva su confirmación irreversible aparte.
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
            case SUPPORT -> "abrir una incidencia para un agente humano";
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

        // Solo se pre-arma el gate de incidencia cuando la sospecha es razonable (DOUBT):
        // así, si el usuario confirma, se crea sin volver a preguntar. En REJECT no se siembra
        // nada (liderar con alternativas sería incoherente con dejar el ticket a medias).
        if (suspected == Intent.SUPPORT && lead) {
            updates.put("ticketDraft", state.userMessage().trim());
            updates.put("awaiting", Awaiting.TICKET_CONFIRM.name());
        } else {
            updates.put("awaiting", Awaiting.NOTHING.name());
        }
        return updates;
    }
}
