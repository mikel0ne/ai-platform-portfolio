package io.github.mikel0ne.l1copilot.dto;

/**
 * Respuesta de un turno del agente.
 *
 * @param intent   intención vigente (útil para depurar y para el badge de la UI)
 * @param resolved true si el turno cerró la gestión (envío informado, pedido
 *                 cancelado, ticket creado)
 */
public record ChatResponse(String conversationId, String reply, String intent, boolean resolved) {
}
