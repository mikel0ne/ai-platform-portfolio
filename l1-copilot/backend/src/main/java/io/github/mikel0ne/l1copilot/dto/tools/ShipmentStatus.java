package io.github.mikel0ne.l1copilot.dto.tools;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * Estado de envío de un pedido concreto.
 */
public record ShipmentStatus(Long orderId, String description, String status,
                             LocalDate estimatedDelivery, String carrier, String imageUrl)
        implements Serializable {
}
