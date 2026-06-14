package io.github.mikel0ne.l1copilot.agent.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Router semántico: clasifica el mensaje del usuario y extrae entidades.
 * AiService de LangChain4j con salida estructurada; la decisión de flujo
 * posterior es código Java ({@code SupportRouting}).
 */
public interface IntentClassifier {

    @SystemMessage("""
            Eres el router de un agente de soporte de un e-commerce. Analiza el mensaje del \
            usuario y devuelve un JSON con la intención y las entidades detectadas.

            Intenciones posibles:
            - TRACKING: quiere saber dónde está un pedido o cuándo llega.
            - CANCEL: quiere cancelar o anular un pedido.
            - SUPPORT: reporta un problema o incidencia (producto roto, no funciona, queja).
            - OTHER: saludos, agradecimientos o cualquier cosa fuera de esas tres.

            Contexto de la conversación:
            - Intención vigente del turno anterior: {{currentIntent}}
            - El agente dejó pendiente: {{awaiting}}
            - Pedidos ya ofrecidos al usuario para elegir: {{candidates}}

            Reglas:
            1. Si AWAITING es ORDER_CHOICE y el usuario está eligiendo uno de los pedidos \
            ofrecidos (por número o por descripción, p.ej. "el portátil"), mantén la intención \
            vigente y devuelve en orderId el id del pedido elegido.
            2. Si el usuario menciona explícitamente un número de pedido, devuélvelo en orderId.
            3. Si AWAITING es CANCEL_CONFIRM, interpreta si el usuario confirma (confirmation=YES), \
            rechaza (confirmation=NO) o dice otra cosa (confirmation=NONE).
            4. Si el usuario cambia de tema, devuelve la nueva intención sin miedo.
            5. orderId debe ser null si no hay un pedido claramente identificado. Nunca lo inventes.
            """)
    @UserMessage("{{message}}")
    RouterDecision classify(@V("message") String message,
                            @V("currentIntent") String currentIntent,
                            @V("awaiting") String awaiting,
                            @V("candidates") String candidates);

    /**
     * Salida estructurada del router.
     *
     * @param intent       TRACKING | CANCEL | SUPPORT | OTHER
     * @param orderId      id del pedido si está claramente identificado; null en caso contrario
     * @param confirmation YES | NO | NONE — solo relevante cuando se esperaba una confirmación
     */
    record RouterDecision(String intent, Long orderId, String confirmation) {
    }
}
