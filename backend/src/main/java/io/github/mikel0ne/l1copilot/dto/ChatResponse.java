package io.github.mikel0ne.l1copilot.dto;

import java.util.List;

/**
 * Respuesta de un turno del agente.
 *
 * @param intent   intención vigente (útil para depurar y para el badge de la UI)
 * @param resolved true si el turno cerró la gestión (envío informado, pedido
 *                 cancelado, ticket creado)
 * @param orders   lista estructurada de tarjetas de pedido para renderizado HTML
 *                 (presente cuando el agente muestra un listado o el detalle de un pedido)
 * @param tickets  tarjetas de ticket (enlaces clicables al detalle) creadas en el turno
 * @param returns  tarjetas de devolución (enlaces clicables a la etiqueta) creadas en el turno
 * @param confirm  acción de confirmación (botones) cuando el turno espera una
 *                 autorización irreversible; null en caso contrario
 */
public record ChatResponse(String conversationId, String reply, String intent, boolean resolved,
                           List<OrderCardDto> orders, List<TicketCardDto> tickets,
                           List<ReturnCardDto> returns, ConfirmActionDto confirm) {
}
