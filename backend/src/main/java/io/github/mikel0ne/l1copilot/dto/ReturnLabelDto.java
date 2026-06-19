package io.github.mikel0ne.l1copilot.dto;

import io.github.mikel0ne.l1copilot.domain.Order;

/**
 * Etiqueta de devolución para la página de detalle del frontend
 * ({@code /returns/{id}}). Reúne todo lo que aparece en una etiqueta real:
 * código de devolución, dirección del almacén de destino, transportista,
 * referencia y valor del código de barras.
 * <p>
 * El almacén de destino y el transportista son datos fijos del prototipo
 * (siempre los mismos): basta con que la etiqueta sea verosímil.
 *
 * @param id              id del pedido devuelto (id de la etiqueta)
 * @param code            código legible de la devolución (p. ej. {@code RET-0789})
 * @param orderDescription descripción del producto devuelto
 * @param status          estado de la devolución
 * @param carrier         transportista que recoge/admite la devolución
 * @param destinationName nombre del almacén de destino
 * @param destinationAddress dirección del almacén de destino
 * @param barcode         valor numérico del código de barras
 * @param createdAt       fecha de inicio en formato ISO-8601
 */
public record ReturnLabelDto(Long id, String code, String orderDescription, String status,
                             String carrier, String destinationName, String destinationAddress,
                             String barcode, String createdAt) {

    /** Almacén de devoluciones (fijo en el prototipo). */
    public static final String DESTINATION_NAME = "Almacén Central de Devoluciones";
    public static final String DESTINATION_ADDRESS = "Polígono Industrial Las Mercedes, Nave 7 · 28022 Madrid";
    public static final String CARRIER = "MRW";

    public static ReturnLabelDto from(Order order) {
        return new ReturnLabelDto(
                order.getId(),
                ReturnCardDto.code(order.getId()),
                order.getDescription(),
                order.getStatus().name(),
                CARRIER,
                DESTINATION_NAME,
                DESTINATION_ADDRESS,
                barcodeFor(order.getId()),
                order.getOrderDate() != null ? order.getOrderDate().toString() : null);
    }

    /** Valor de código de barras determinista a partir del id de pedido: siempre el mismo. */
    public static String barcodeFor(long orderId) {
        return String.format("%013d", 8400000000000L + orderId);
    }
}

