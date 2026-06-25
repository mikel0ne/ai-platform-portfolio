package io.github.mikel0ne.l1copilot.dto;

/**
 * Tarjeta de ticket estructurada que se devuelve al frontend para renderizarla
 * como un enlace clicable. Al pulsarla, la interfaz abre el detalle del ticket
 * en una ventana emergente ({@code /tickets/{id}}).
 *
 * @param id        identificador interno del ticket
 * @param code      código legible del ticket (p. ej. {@code SUP-0005})
 * @param title     asunto/título del ticket
 * @param status    estado del ticket ({@code OPEN}, {@code IN_PROGRESS}, {@code CLOSED})
 * @param createdAt fecha de creación en formato ISO-8601
 */
public record TicketCardDto(Long id, String code, String title, String status, String createdAt) {

    /** Formatea el código legible del ticket a partir de su id: {@code SUP-0005}. */
    public static String code(long id) {
        return String.format("SUP-%04d", id);
    }
}
