package io.github.mikel0ne.l1copilot.dto;

/**
 * Tarjeta de devolución estructurada que se devuelve al frontend para
 * renderizarla como un enlace clicable. Al pulsarla, la interfaz abre la
 * etiqueta de devolución en una ventana emergente ({@code /returns/{id}}),
 * igual que la tarjeta de un ticket abre su detalle.
 *
 * @param id          identificador del pedido devuelto (sirve de id de la etiqueta)
 * @param code        código legible de la devolución (p. ej. {@code RET-0789})
 * @param description descripción del producto devuelto
 * @param status      estado de la devolución ({@code RETURN_REQUESTED})
 * @param createdAt   fecha de inicio en formato ISO-8601
 */
public record ReturnCardDto(Long id, String code, String description, String status, String createdAt) {

    /** Formatea el código legible de la devolución a partir del id de pedido: {@code RET-0789}. */
    public static String code(long orderId) {
        return String.format("RET-%04d", orderId);
    }
}

