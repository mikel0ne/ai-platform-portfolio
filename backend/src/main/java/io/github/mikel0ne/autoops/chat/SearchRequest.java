package io.github.mikel0ne.autoops.chat;

import jakarta.validation.constraints.NotBlank;

/**
 * Petición del chat de búsqueda (doc 05 §2). Sin autenticación en el MVP (solo lectura de documentación
 * interna).
 */
public record SearchRequest(@NotBlank String consulta) {}
