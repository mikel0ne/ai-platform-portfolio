package io.github.mikel0ne.l1copilot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Turno de conversación. El {@code conversationId} lo genera el frontend en el
 * primer mensaje (UUID) y lo repite en los siguientes: es el threadId del grafo.
 */
public record ChatRequest(
        @NotBlank @Size(max = 64) String conversationId,
        @NotBlank @Size(max = 2000) String message) {
}
