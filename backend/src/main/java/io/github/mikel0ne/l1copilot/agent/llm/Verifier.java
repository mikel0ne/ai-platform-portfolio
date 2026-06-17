package io.github.mikel0ne.l1copilot.agent.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Segunda opinión antes de ejecutar una acción. El router clasifica; el
 * verificador juzga —de forma binaria y estrecha— si esa acción es CLARAMENTE
 * lo que el usuario pide. Su único cometido es decidir SEGURO/DUDA: ni elige
 * opciones ni redacta nada (eso es de {@code ClarifyNode} + {@code Responder}).
 * <p>
 * Es la red que evita "actuar a lo loco" con un modelo flojo: ante cualquier
 * ambigüedad razonable devuelve DUDA y el flujo pregunta en vez de ejecutar.
 */
public interface Verifier {

    @SystemMessage("""
            Eres el verificador de un agente de soporte de e-commerce. El router ha clasificado el \
            mensaje del usuario en una gestión. Tu ÚNICA tarea es juzgar si esa gestión es lo que el \
            usuario pide. No propongas alternativas ni redactes respuestas: solo emite un veredicto.

            Devuelve un JSON con:
            - verdict: uno de tres niveles
              · "SURE"   → claramente es eso, sin ambigüedad.
              · "DOUBT"  → plausible pero ambiguo (podría ser otra cosa: una pregunta general, un \
            comentario, una queja sin petición concreta, una duda sobre la propia conversación).
              · "REJECT" → claramente NO es eso ("ni de coña"): el mensaje no encaja con la gestión.
            - reason: una frase muy breve justificándolo.

            Umbral ASIMÉTRICO según el riesgo de la gestión:
            - GESTIONES DE LECTURA (TRACKING: consultar/listar el envío de pedidos): son inocuas. Sé \
            GENEROSO con SURE; no te pongas tímido ante una consulta normal ("¿dónde está mi \
            pedido?", "¿ha salido ya?"). Reserva DOUBT para mensajes de verdad ambiguos y REJECT \
            solo cuando el mensaje claramente no va de consultar pedidos.
            - ACCIONES (CANCEL: cancelar un pedido; SUPPORT: abrir una incidencia a un agente \
            humano): son COSTOSAS. Sé ESTRICTO. Ante cualquier duda razonable, DOUBT; y si el \
            mensaje claramente no pide esa acción (queja vaga, pregunta general, comentario), \
            REJECT. Siempre es mejor preguntar que ejecutar algo no pedido.

            Guías:
            - Problema concreto y explícito (producto roto, pedido no recibido) con intención de \
            gestionarlo => SURE para SUPPORT.
            - Queja vaga o desahogo sin problema concreto, frente a SUPPORT => REJECT.
            - Pregunta general que respondería una política de la tienda, frente a SUPPORT/CANCEL => REJECT.
            - Orden clara de cancelar un pedido identificado => SURE para CANCEL.
            - Mensaje meta o ambiguo ("¿y ahora qué?", "¿qué hago con esto?") frente a una acción => REJECT.
            """)
    @UserMessage("""
            GESTIÓN QUE SE VA A REALIZAR: {{intent}}
            PEDIDO EN FOCO: {{focus}}
            MENSAJE DEL USUARIO: {{message}}
            """)
    Decision verify(@V("message") String message,
                    @V("intent") String intent,
                    @V("focus") String focus);

    /**
     * Veredicto del verificador.
     *
     * @param verdict SURE | DOUBT | REJECT
     * @param reason  justificación breve (para logs y depuración)
     */
    record Decision(String verdict, String reason) {
    }
}
