package io.github.mikel0ne.l1copilot.agent;

/**
 * Navegación explícita que el usuario pide sobre el "árbol" de la conversación,
 * al margen de la intención. Existen dos salidas del foco, con destinos distintos:
 * volver al listado de pedidos para elegir otro ("mis pedidos", "ver todos", "otro"),
 * o volver al menú principal de capacidades ("menú principal", "qué puedo hacer",
 * "ayuda", "empezar de nuevo").
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
    BACK_TO_MENU;

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
