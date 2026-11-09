package io.github.mikel0ne.l1copilot.dto;

import io.github.mikel0ne.l1copilot.domain.Order;

public record ReturnLabelDto(Long id, String code, String orderDescription, String status,
                             String carrier, String destinationName, String destinationAddress,
                             String barcode, String createdAt) {
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

    public static String barcodeFor(long orderId) {
        return String.format("%013d", 8400000000000L + orderId);
    }
}
