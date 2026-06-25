package io.github.mikel0ne.l1copilot.dto;

/**
 * Tarjeta de pedido estructurada que se devuelve al frontend para renderizado HTML.
 * Incluye imageUrl para mostrar la imagen del producto en la interfaz.
 */
public record OrderCardDto(Long id, String description, String status, String imageUrl) {
}
