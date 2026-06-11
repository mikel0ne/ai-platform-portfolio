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
    public static final String TO_FALLBACK = "fallback";
    public static final String TO_ASK_USER = "ask_user";

    private SupportRouting() {
    }

    /** Tras el router: a qué skill (o a desambiguación) va el turno. */
    public static String fromRouter(SupportState state) {
        return switch (state.intent()) {
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
