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
    public static final String TO_SUPPORT = "support";
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
            case SUPPORT -> needsVerify(state) ? TO_VERIFY : TO_SUPPORT;
            case FAQ -> TO_FAQ;
            case CHATTY -> TO_CHATTY;
            default -> TO_FALLBACK;
        };
    }

    /**
     * ¿Hay que pasar la gestión por verify? Solo en peticiones frescas: sin pregunta
     * pendiente y sin una incidencia ya en curso. Así una respuesta a un gate (elegir
     * pedido, confirmar cancelación, describir la incidencia) NO se re-verifica.
     */
    private static boolean needsVerify(SupportState state) {
        return state.awaiting() == Awaiting.NOTHING && state.ticketDraft().isBlank();
    }

    /**
     * Tras verify, respuesta graduada según el veredicto y el riesgo de la gestión:
     * <ul>
     *   <li>REJECT ("ni de coña") → {@code clarify}, sea lectura o acción.</li>
     *   <li>DOUBT en una ACCIÓN (CANCEL/SUPPORT) → {@code clarify}: se pregunta antes de
     *   tocar nada.</li>
     *   <li>DOUBT en una LECTURA (TRACKING) → se sigue adelante (con tono tentativo, que
     *   fija verify); ser inocua hace barato equivocarse.</li>
     *   <li>SURE → flujo normal de la gestión.</li>
     * </ul>
     */
    public static String fromVerify(SupportState state) {
        Verdict verdict = state.verdict();
        Intent intent = state.intent();
        boolean action = intent == Intent.CANCEL || intent == Intent.SUPPORT;

        if (verdict == Verdict.REJECT || (verdict == Verdict.DOUBT && action)) {
            return TO_CLARIFY;
        }
        return switch (intent) {
            case TRACKING -> state.orderId().isPresent() ? TO_TRACKING : TO_RESOLVE_ORDER;
            case CANCEL -> state.orderId().isPresent() ? TO_CANCEL_CHECK : TO_RESOLVE_ORDER;
            case SUPPORT -> TO_SUPPORT;
            default -> TO_FALLBACK;
        };
    }

    /** Tras buscar candidatos: si hay pedido único sigue el flujo; si no, preguntar. */
    public static String fromResolveOrder(SupportState state) {
        if (state.orderId().isEmpty()) {
            return TO_ASK_USER;
        }
        return state.intent() == Intent.CANCEL ? TO_CANCEL_CHECK : TO_TRACKING;
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
}
