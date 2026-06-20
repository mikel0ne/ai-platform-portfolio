package io.github.mikel0ne.l1copilot.agent;

/**
 * Aristas condicionales del grafo: funciones puras sobre el estado.
 * El LLM clasifica (nodo router), pero las decisiones de enrutado son
 * Java determinista y testeable sin modelo.
 */
public final class SupportRouting {

    /** Destinos lógicos que las aristas devuelven (se mapean a nodos en el builder). */
    public static final String TO_RESOLVE_ORDER = "resolve_order";
    public static final String TO_TRACKING = "tracking";
    public static final String TO_CANCEL_CHECK = "cancel_check";
    public static final String TO_CANCEL_EXEC = "execute";
    public static final String TO_RETURN_CHECK = "return_check";
    public static final String TO_RETURN_EXEC = "return_exec";
    public static final String TO_SUPPORT = "support";
    public static final String TO_TICKETS = "tickets";
    public static final String TO_FAQ = "faq";
    public static final String TO_CHATTY = "chatty";
    public static final String TO_FALLBACK = "fallback";
    public static final String TO_ASK_USER = "ask_user";
    public static final String TO_VERIFY = "verify";
    public static final String TO_CLARIFY = "clarify";

    private SupportRouting() {
    }

    /**
     * Tras el router: a qué skill (o a desambiguación) va el turno.
     * <p>
     * Las acciones (CANCEL, SUPPORT) pasan antes por {@code verify} —segunda opinión
     * antes de ejecutar— pero SOLO si la petición es "fresca" ({@link #needsVerify}):
     * cuando el usuario está respondiendo a una pregunta nuestra (hay algo pendiente o
     * un borrador de incidencia a medias) ya no se vuelve a verificar ni a preguntar.
     */
    public static String fromRouter(SupportState state) {
        return switch (state.intent()) {
            case TRACKING -> needsVerify(state)
                    ? TO_VERIFY
                    : (state.orderId().isPresent() ? TO_TRACKING : TO_RESOLVE_ORDER);
            case CANCEL -> needsVerify(state)
                    ? TO_VERIFY
                    : (state.orderId().isPresent() ? TO_CANCEL_CHECK : TO_RESOLVE_ORDER);
            case RETURN -> needsVerify(state)
                    ? TO_VERIFY
                    : (state.orderId().isPresent() ? TO_RETURN_CHECK : TO_RESOLVE_ORDER);
            // SUPPORT NO pasa por verify: su salvaguarda es el propio gate de confirmación
            // humano-en-el-bucle (proponer → confirmar → describir). Si es sobre un pedido sin foco,
            // primero se identifica el pedido (resolve_order); si no, va directo al gate.
            case SUPPORT -> supportNeedsOrder(state) ? TO_RESOLVE_ORDER : TO_SUPPORT;
            case TICKETS -> TO_TICKETS;
            case FAQ -> TO_FAQ;
            case CHATTY -> TO_CHATTY;
            default -> TO_FALLBACK;
        };
    }

    /**
     * ¿Hay que pasar la gestión por verify? Solo en peticiones frescas: sin pregunta
     * pendiente, sin una incidencia ya en curso y que NO sean una navegación. Así una
     * respuesta a un gate (elegir pedido, confirmar cancelación, describir la incidencia)
     * o una navegación explícita ("volver al listado") NO se re-verifican: la navegación
     * fuerza TRACKING/CANCEL solo para re-listar, no para ejecutar nada, y el verificador
     * la rechazaría al re-leer el "volver atrás" como si fuera una consulta de pedidos.
     */
    private static boolean needsVerify(SupportState state) {
        return state.awaiting() == Awaiting.NOTHING
                && state.ticketDraft().isBlank()
                && !state.navigating();
    }

    /**
     * Una incidencia identifica primero el pedido (igual que CANCEL/RETURN/TRACKING) SOLO cuando es
     * sobre un pedido ({@code orderRelated}) y aún no hay foco, con la gestión fresca (sin gate de
     * incidencia ni borrador en curso). A diferencia de cancelación/devolución —que SIEMPRE son
     * sobre un pedido— un ticket de soporte puede ser genérico (cuenta, web, facturación sin pedido):
     * en ese caso ({@code orderRelated == false}) NO se identifica nada y se abre la incidencia suelta.
     */
    private static boolean supportNeedsOrder(SupportState state) {
        return state.intent() == Intent.SUPPORT
                && state.orderRelated()
                && state.orderId().isEmpty()
                && state.awaiting() == Awaiting.NOTHING
                && state.ticketDraft().isBlank();
    }

    /**
     * Tras verify, respuesta graduada según el veredicto y el riesgo de la gestión:
     * <ul>
     *   <li>REJECT ("ni de coña") → {@code clarify}, sea lectura o acción.</li>
     *   <li>DOUBT en una ACCIÓN (CANCEL/RETURN) → {@code clarify}: se pregunta antes de
     *   tocar nada.</li>
     *   <li>DOUBT en una LECTURA (TRACKING) → se sigue adelante (con tono tentativo, que
     *   fija verify); ser inocua hace barato equivocarse.</li>
     *   <li>SURE → flujo normal de la gestión.</li>
     * </ul>
     * Nota: SUPPORT no pasa por el nodo {@code verify} del grafo (el router lo envía directamente
     * a {@code support}), pero SupportNode llama internamente al Verifier; esta arista cubre el
     * caso en que SUPPORT llegase a verify por otra ruta futura y garantiza coherencia en los tests
     * unitarios de routing.
     */
    public static String fromVerify(SupportState state) {
        Verdict verdict = state.verdict();
        Intent intent = state.intent();

        if (!proceedsAfterVerify(verdict, intent)) {
            return TO_CLARIFY;
        }
        return switch (intent) {
            case TRACKING -> state.orderId().isPresent() ? TO_TRACKING : TO_RESOLVE_ORDER;
            case CANCEL -> state.orderId().isPresent() ? TO_CANCEL_CHECK : TO_RESOLVE_ORDER;
            case RETURN -> state.orderId().isPresent() ? TO_RETURN_CHECK : TO_RESOLVE_ORDER;
            case SUPPORT -> TO_SUPPORT;
            default -> TO_FALLBACK;
        };
    }

    /**
     * ¿El veredicto deja seguir adelante con la gestión (en vez de ir a {@code clarify})?
     * REJECT siempre frena; DOUBT frena solo en ACCIONES (CANCEL/RETURN/SUPPORT), porque tocar
     * algo irreversible ante la duda es caro; en una LECTURA (TRACKING) la duda sigue adelante
     * con tono tentativo. Es la condición que comparten la arista {@link #fromVerify} y el commit
     * diferido del foco en {@code VerifyNode}: ambos deben coincidir en "seguir o no seguir".
     */
    public static boolean proceedsAfterVerify(Verdict verdict, Intent intent) {
        boolean action = intent == Intent.CANCEL || intent == Intent.RETURN || intent == Intent.SUPPORT;
        return !(verdict == Verdict.REJECT || (verdict == Verdict.DOUBT && action));
    }

    /** Tras buscar candidatos: si hay pedido único sigue el flujo; si no, preguntar. */
    public static String fromResolveOrder(SupportState state) {
        if (state.orderId().isEmpty()) {
            // SUPPORT que llegó aquí pero el usuario no tiene pedidos que identificar (awaiting
            // NOTHING, no un menú pendiente): se abre una incidencia genérica en vez de dejarle
            // sin vía. El caso de varios pedidos (awaiting ORDER_CHOICE) sí cae en ask_user.
            if (state.intent() == Intent.SUPPORT && state.awaiting() == Awaiting.NOTHING) {
                return TO_SUPPORT;
            }
            return TO_ASK_USER;
        }
        return switch (state.intent()) {
            case CANCEL -> TO_CANCEL_CHECK;
            case RETURN -> TO_RETURN_CHECK;
            case SUPPORT -> TO_SUPPORT;
            default -> TO_TRACKING;
        };
    }

    /**
     * Tras comprobar la cancelación: solo se ejecuta si las reglas lo permiten
     * Y el usuario confirmó explícitamente en este turno.
     */
    public static String fromCancelCheck(SupportState state) {
        return state.cancellable() && "YES".equals(state.confirmation())
                ? TO_CANCEL_EXEC
                : TO_ASK_USER;
    }

    /**
     * Tras comprobar la devolución: solo se inicia si las reglas lo permiten
     * Y el usuario confirmó explícitamente en este turno (mismo criterio que la cancelación).
     */
    public static String fromReturnCheck(SupportState state) {
        return state.returnable() && "YES".equals(state.confirmation())
                ? TO_RETURN_EXEC
                : TO_ASK_USER;
    }
}
