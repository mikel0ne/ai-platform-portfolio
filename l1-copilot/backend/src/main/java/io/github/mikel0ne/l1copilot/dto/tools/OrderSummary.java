package io.github.mikel0ne.l1copilot.dto.tools;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * Resumen de pedido que las tools devuelven al agente.
 * Serializable: viaja dentro del estado del grafo (checkpointer).
 */
public record OrderSummary(Long id, String description, String status, LocalDate orderDate, String imageUrl)
        implements Serializable {
}
