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
            - SUPPORT: reporta un PROBLEMA CONCRETO con un producto o pedido que requiere la \
            intervención de un agente humano y que NO se resuelve consultando el envío ni con \
            información general de la tienda: producto defectuoso, roto o dañado, artículo \
            equivocado, pedido que figura entregado pero no ha llegado, cobro incorrecto, etc. \
            La mera frustración, queja o desahogo ("vaya servicio", "esto es un desastre") SIN \
            un problema concreto NO es SUPPORT. Tampoco lo son las preguntas sobre la propia \
            conversación o sobre una incidencia ya abierta ("¿qué hago ahora?", "¿y esto qué?", \
            "¿qué pasa con la incidencia?"): eso es OTHER.
            - FAQ: pregunta general sobre políticas de la tienda (envíos, devoluciones, pagos, \
            garantía, plazos, contacto) que NO es sobre un pedido concreto. Si la duda del \
            usuario se puede responder con políticas de la tienda, es FAQ, no SUPPORT.
            - CHATTY: charla informal o personal hacia el asistente, sin relación con la tienda \
            ni con ningún pedido (p.ej. "¿cómo te llamas?", "¿qué tal tu día?", "¿a qué hora \
            cierras?", una broma). NO es una acción de negocio: no cambies el foco ni el orderId \
            por ella.
            - OTHER: saludos iniciales, agradecimientos, peticiones de ayuda/capacidades poco \
            claras, o mensajes ambiguos/meta que no encajan en las anteriores. Ante la duda, OTHER.

            Contexto de la conversación:
            - Intención vigente del turno anterior: {{currentIntent}}
            - El agente dejó pendiente: {{awaiting}}
            - Pedidos ya ofrecidos al usuario para elegir: {{candidates}}
            - Pedido sobre el que ya se está trabajando (foco actual): {{focusOrder}}

            Reglas:
            1. Si AWAITING es ORDER_CHOICE y el usuario está eligiendo uno de los pedidos \
            ofrecidos (por número o por descripción, p.ej. "el portátil"), mantén la intención \
            vigente y devuelve en orderId el id del pedido elegido.
            2. Si el usuario menciona explícitamente un número de pedido, devuélvelo en orderId.
            3. FOCO PEGAJOSO: si ya hay un pedido en foco (foco distinto de "(ninguno)") y AWAITING \
            es NOTHING, NO devuelvas orderId salvo que el usuario mencione EXPLÍCITAMENTE otro \
            pedido distinto (por número o por descripción inequívoca). Si el usuario sigue \
            hablando del mismo pedido —aunque cambie de intención (p.ej. "no me ha llegado", \
            "quiero cancelarlo")—, devuelve orderId=null para conservar el foco. Nunca elijas un \
            pedido por parecido temático con su descripción o estado.
            4. Si AWAITING es CANCEL_CONFIRM, interpreta si el usuario confirma (confirmation=YES), \
            rechaza (confirmation=NO) o dice otra cosa (confirmation=NONE).
            4b. PRUDENCIA CON LAS ACCIONES: CANCEL y SUPPORT disparan acciones reales (cancelar un \
            pedido, abrir una incidencia a un agente humano). Solo elígelas cuando el usuario las \
            pida de forma CLARA e inequívoca. Ante la duda entre SUPPORT y otra cosa, NO elijas \
            SUPPORT: prefiere FAQ si una política de la tienda responde, TRACKING si en realidad \
            pregunta por el envío, u OTHER para pedir aclaración. Siempre es mejor preguntar o \
            informar que abrir una incidencia que el usuario no ha pedido.
            4c. Si AWAITING es TICKET_CONFIRM o TICKET_INFO, ya se le propuso abrir una incidencia y \
            se espera que confirme y describa el problema. Si confirma o aporta la descripción del \
            problema, devuelve intent=SUPPORT. Si la rechaza ("no", "déjalo", "olvídalo") o pide \
            otra cosa, NO uses SUPPORT: clasifica su intención real (OTHER, CANCEL, TRACKING, FAQ…) \
            para que el sistema descarte la incidencia y le atienda lo que de verdad quiere.
            5. La intención puede cambiar libremente (tracking, cancelar, incidencia, FAQ); el \
            foco se mantiene salvo que el usuario lo cambie según la regla 3.
            5b. DIGRESIÓN: aunque AWAITING indique algo pendiente (estás esperando que elija un \
            pedido, confirme una cancelación o describa una incidencia), si el usuario hace en su \
            lugar una pregunta general de políticas, clasifícala como FAQ; y si suelta un \
            comentario de charla informal, como CHATTY. La gestión pendiente se retomará después \
            automáticamente; tú solo marca FAQ/CHATTY y no inventes orderId.
            6. NAVEGACIÓN: hay dos salidas posibles del foco actual, distintas entre sí:
               - BACK_TO_LIST: el usuario pide ver o volver al listado de sus pedidos, o elegir \
            otro pedido (p.ej. "mis pedidos", "ver todos", "otro pedido", "enséñame el listado").
               - BACK_TO_MENU: el usuario pide EXPLÍCITAMENTE volver al menú principal o \
            empezar de cero (p.ej. "menú principal", "volver al inicio", "empezar de nuevo"). \
            OJO: "¿qué puedo hacer?", "¿qué podemos hacer?", "ayuda" o un "volver" a secas en \
            mitad de una gestión NO son navegación —el usuario suele preguntar qué puede hacer \
            con el pedido en cuestión—; en esos casos navigation=NONE.
               En cualquier otro caso, navigation=NONE.
            7. orderId debe ser null si no hay un pedido claramente identificado. Nunca lo inventes.

            Ejemplos (mensaje → intención correcta):
            - "¿dónde está mi pedido?" → TRACKING
            - "quiero cancelar el pedido 200" → CANCEL (orderId=200)
            - "el monitor que pedí llegó con la pantalla rota" → SUPPORT (problema concreto)
            - "¿cuánto tardan las devoluciones?" → FAQ (lo responde una política, no es incidencia)
            - "menudo desastre de tienda, ya me diréis" → OTHER (queja/desahogo SIN problema concreto)
            - "¿y ahora qué hago con esto?" → OTHER (pregunta meta, no una acción nueva)
            - "¿qué hago con la incidencia que has creado?" → OTHER (es sobre una incidencia ya abierta)
            - "ah no, me equivoqué, no era el portátil sino los auriculares" → mantén la gestión vigente \
            y deja que el foco/menú lo resuelva; NO es SUPPORT
            - "gracias, muy amable" → OTHER
            - "¿cómo te llamas?" → CHATTY
            """)
    @UserMessage("{{message}}")
    RouterDecision classify(@V("message") String message,
                            @V("currentIntent") String currentIntent,
                            @V("awaiting") String awaiting,
                            @V("candidates") String candidates,
                            @V("focusOrder") String focusOrder);

    /**
     * Salida estructurada del router.
     *
     * @param intent       TRACKING | CANCEL | SUPPORT | FAQ | CHATTY | OTHER
     * @param orderId      id del pedido si el usuario lo identifica claramente; null para
     *                     conservar el foco actual (no inventar ni elegir por similitud)
     * @param confirmation YES | NO | NONE — solo relevante cuando se esperaba una confirmación
     * @param navigation   NONE | BACK_TO_LIST | BACK_TO_MENU — el usuario pide volver al
     *                     listado de pedidos o al menú principal
     */
    record RouterDecision(String intent, Long orderId, String confirmation, String navigation) {
    }
}
