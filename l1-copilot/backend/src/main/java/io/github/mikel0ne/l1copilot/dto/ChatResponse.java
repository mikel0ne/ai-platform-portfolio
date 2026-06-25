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
 * @param ordersSelectable true si las tarjetas de {@code orders} son un LISTADO ofrecido
 *                 para elegir (la UI debe hacerlas clicables); false si son el detalle del
 *                 pedido ya en foco (no clicables: el usuario ya está dentro de ese pedido).
 *                 Es la distinción determinista listado-vs-foco, no depende del nº de tarjetas.
 * @param tickets  tarjetas de ticket (enlaces clicables al detalle) creadas en el turno
 * @param returns  tarjetas de devolución (enlaces clicables a la etiqueta) creadas en el turno
 * @param confirm  acción de confirmación (botones) cuando el turno espera una
 *                 autorización irreversible; null en caso contrario
 */
public record ChatResponse(String conversationId, String reply, String intent, boolean resolved,
                           List<OrderCardDto> orders, boolean ordersSelectable,
                           List<TicketCardDto> tickets,
                           List<ReturnCardDto> returns, ConfirmActionDto confirm) {
}
