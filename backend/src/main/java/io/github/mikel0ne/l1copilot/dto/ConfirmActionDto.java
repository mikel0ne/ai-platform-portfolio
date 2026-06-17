package io.github.mikel0ne.l1copilot.dto;

/**
 * Acción de confirmación que la interfaz debe renderizar como botones para una
 * operación crítica e irreversible (hoy, la cancelación de un pedido). El clic en
 * un botón envía su {@code payload} como mensaje: el backend lo interpreta de forma
 * determinista, sin pasar por el LLM.
 *
 * @param orderId       pedido afectado
 * @param title         pregunta de confirmación a mostrar
 * @param warning       aviso de irreversibilidad
 * @param confirmLabel  texto del botón que confirma
 * @param confirmPayload mensaje que envía la UI al confirmar
 * @param cancelLabel   texto del botón que descarta
 * @param cancelPayload mensaje que envía la UI al descartar
 */
public record ConfirmActionDto(Long orderId, String title, String warning,
                               String confirmLabel, String confirmPayload,
                               String cancelLabel, String cancelPayload) {
}
