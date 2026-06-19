package io.github.mikel0ne.l1copilot.agent;

/**
 * Navegación explícita que el usuario pide sobre el "árbol" de la conversación,
 * al margen de la intención. La conversación tiene tres niveles jerárquicos:
 * <pre>
 *   Menú principal (capacidades)  →  Listado de pedidos  →  Pedido concreto (foco)
 * </pre>
 * Hay dos salidas explícitas del foco, con destinos distintos: volver al listado
 * de pedidos para elegir otro ("mis pedidos", "ver todos", "otro"), o volver al
 * menú principal de capacidades ("menú principal", "empezar de nuevo").
 * <p>
 * Y existe la navegación ambigua de "subir un nivel" ({@link #BACK}): un "atrás"
 * o "volver" a secas, donde el usuario NO dice el destino. En ese caso el destino
 * NO lo decide el LLM, sino {@code RouterNode} según el nivel real en que está la
 * conversación: desde un pedido en foco se vuelve al listado; estando ya en el
 * listado (o sin foco) se vuelve al menú principal. Así "atrás" siempre sube
 * exactamente un nivel y nunca se queda dando vueltas en el mismo sitio.
 * <p>
 * Es lo que permite que el foco ({@code orderId}) sea pegajoso —no se salta de
 * pedido en pedido— pero el usuario conserve siempre una vía clara para subir
 * de nuevo, ya sea al listado de pedidos o al menú principal.
 */
public enum Navigation {
    /** El usuario sigue en el flujo actual; no pide cambiar de nivel. */
    NONE,
    /** El usuario pide volver al listado de sus pedidos (soltar el foco actual). */
    BACK_TO_LIST,
    /** El usuario pide volver al menú principal de capacidades (soltar el foco actual). */
    BACK_TO_MENU,
    /**
     * "Atrás" / "volver" a secas: SUBIR UN NIVEL sin que el usuario diga el destino.
     * Lo resuelve {@code RouterNode} a {@link #BACK_TO_LIST} o {@link #BACK_TO_MENU}
     * según el nivel actual (si hay un pedido en foco o no).
     */
    BACK;

    /** Parseo tolerante de la salida del LLM: cualquier cosa no reconocida es NONE. */
    public static Navigation fromLlm(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
