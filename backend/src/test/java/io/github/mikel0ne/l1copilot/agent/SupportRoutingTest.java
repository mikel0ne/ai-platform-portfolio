package io.github.mikel0ne.l1copilot.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las aristas condicionales son funciones puras sobre el estado:
 * se testean sin LLM, sin Spring y sin base de datos.
 */
class SupportRoutingTest {

    private SupportState state(Map<String, Object> data) {
        return new SupportState(new HashMap<>(data));
    }

    // ---- fromRouter ----

    @Test
    @DisplayName("TRACKING fresco pasa por verify (con o sin orderId)")
    void trackingFreshGoesToVerify() {
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "TRACKING"))))
                .isEqualTo(SupportRouting.TO_VERIFY);
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "TRACKING", "orderId", 123L))))
                .isEqualTo(SupportRouting.TO_VERIFY);
    }

    @Test
    @DisplayName("TRACKING respondiendo a una elección de pedido NO re-verifica: va directo")
    void trackingMidFlowSkipsVerify() {
        assertThat(SupportRouting.fromRouter(state(Map.of(
                "intent", "TRACKING", "awaiting", "ORDER_CHOICE"))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);
        assertThat(SupportRouting.fromRouter(state(Map.of(
                "intent", "TRACKING", "orderId", 123L, "awaiting", "ORDER_CHOICE"))))
                .isEqualTo(SupportRouting.TO_TRACKING);
    }

    @Test
    @DisplayName("CANCEL fresco pasa por verify (con o sin orderId)")
    void cancelFreshGoesToVerify() {
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "CANCEL"))))
                .isEqualTo(SupportRouting.TO_VERIFY);
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "CANCEL", "orderId", 200L))))
                .isEqualTo(SupportRouting.TO_VERIFY);
    }

    @Test
    @DisplayName("CANCEL respondiendo a una confirmación pendiente NO re-verifica: va directo")
    void cancelMidFlowSkipsVerify() {
        // Hay algo pendiente (CANCEL_CONFIRM): es una respuesta nuestra, no una petición fresca
        assertThat(SupportRouting.fromRouter(state(Map.of(
                "intent", "CANCEL", "orderId", 200L, "awaiting", "CANCEL_CONFIRM"))))
                .isEqualTo(SupportRouting.TO_CANCEL_CHECK);
    }

    @Test
    @DisplayName("SUPPORT NO pasa por verify: genérica al gate; sobre un pedido sin foco identifica primero")
    void supportRouting() {
        // Incidencia genérica (orderRelated=false): directa al gate de incidencia, sin verify
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "SUPPORT"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
        // Sobre un pedido sin foco → identificar el pedido primero (como CANCEL/RETURN)
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "SUPPORT", "orderRelated", true))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);
        // Sobre un pedido pero con foco ya resuelto → directa al gate, sin re-preguntar
        assertThat(SupportRouting.fromRouter(state(Map.of(
                "intent", "SUPPORT", "orderRelated", true, "orderId", 300L))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
        // En el gate (borrador o pregunta pendiente): directa a support, no re-identifica
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "SUPPORT", "ticketDraft", "algo"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "SUPPORT", "awaiting", "TICKET_CONFIRM"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
        assertThat(SupportRouting.fromRouter(state(Map.of(
                "intent", "SUPPORT", "orderRelated", true, "awaiting", "TICKET_CONFIRM"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
    }

    @Test
    @DisplayName("OTHER/NONE al fallback")
    void fallbackRouting() {
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "OTHER"))))
                .isEqualTo(SupportRouting.TO_FALLBACK);
        assertThat(SupportRouting.fromRouter(state(Map.of())))
                .isEqualTo(SupportRouting.TO_FALLBACK);
    }

    @Test
    @DisplayName("Navegación 'volver al listado' NO re-verifica: va directo a resolve_order")
    void navigationSkipsVerify() {
        // El router fuerza TRACKING/CANCEL para re-listar, pero marca navigating=true: la
        // navegación no es una petición fresca, así que NO debe pasar por verify (que re-leería
        // el "volver atrás" como una consulta y lo rechazaría → clarify en bucle).
        assertThat(SupportRouting.fromRouter(state(Map.of(
                "intent", "TRACKING", "navigating", true))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);
        assertThat(SupportRouting.fromRouter(state(Map.of(
                "intent", "CANCEL", "navigating", true))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);
    }

    // ---- fromVerify ----

    @Test
    @DisplayName("REJECT manda a clarify, sea lectura o acción")
    void verifyRejectGoesToClarify() {
        assertThat(SupportRouting.fromVerify(state(Map.of("intent", "SUPPORT", "verdict", "REJECT"))))
                .isEqualTo(SupportRouting.TO_CLARIFY);
        assertThat(SupportRouting.fromVerify(state(Map.of("intent", "TRACKING", "verdict", "REJECT"))))
                .isEqualTo(SupportRouting.TO_CLARIFY);
    }

    @Test
    @DisplayName("DOUBT en una ACCIÓN va a clarify; en una LECTURA sigue adelante (tono tentativo)")
    void verifyDoubtDependsOnRisk() {
        // Acciones (costosas): DOUBT pregunta antes de tocar nada
        assertThat(SupportRouting.fromVerify(state(Map.of("intent", "SUPPORT", "verdict", "DOUBT"))))
                .isEqualTo(SupportRouting.TO_CLARIFY);
        assertThat(SupportRouting.fromVerify(state(Map.of("intent", "CANCEL", "verdict", "DOUBT"))))
                .isEqualTo(SupportRouting.TO_CLARIFY);
        // Lecturas (inocuas): DOUBT sigue adelante con normalidad
        assertThat(SupportRouting.fromVerify(state(Map.of("intent", "TRACKING", "verdict", "DOUBT"))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);
        assertThat(SupportRouting.fromVerify(state(Map.of(
                "intent", "TRACKING", "orderId", 123L, "verdict", "DOUBT"))))
                .isEqualTo(SupportRouting.TO_TRACKING);
    }

    @Test
    @DisplayName("SURE sigue el flujo normal de la gestión")
    void verifySureContinuesFlow() {
        assertThat(SupportRouting.fromVerify(state(Map.of("intent", "SUPPORT", "verdict", "SURE"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
        assertThat(SupportRouting.fromVerify(state(Map.of("intent", "CANCEL", "verdict", "SURE"))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);
        assertThat(SupportRouting.fromVerify(state(Map.of(
                "intent", "CANCEL", "orderId", 200L, "verdict", "SURE"))))
                .isEqualTo(SupportRouting.TO_CANCEL_CHECK);
        assertThat(SupportRouting.fromVerify(state(Map.of(
                "intent", "TRACKING", "orderId", 123L, "verdict", "SURE"))))
                .isEqualTo(SupportRouting.TO_TRACKING);
    }

    @Test
    @DisplayName("Una intención no reconocida del LLM degrada a fallback, nunca rompe")
    void unknownIntentDegradesToFallback() {
        var s = state(Map.of("intent", "BANANA"));
        assertThat(SupportRouting.fromRouter(s)).isEqualTo(SupportRouting.TO_FALLBACK);
    }

    @Test
    @DisplayName("FAQ va a la skill de preguntas frecuentes")
    void faqRouting() {
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "FAQ"))))
                .isEqualTo(SupportRouting.TO_FAQ);
    }

    @Test
    @DisplayName("TICKETS (consulta de incidencias) es una lectura: va directa, sin verify")
    void ticketsRouting() {
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "TICKETS"))))
                .isEqualTo(SupportRouting.TO_TICKETS);
        // No es una acción: ni siquiera una petición fresca pasa por verify
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "TICKETS", "awaiting", "NOTHING"))))
                .isEqualTo(SupportRouting.TO_TICKETS);
    }

    @Test
    @DisplayName("CHATTY va al modo de charla informal")
    void chattyRouting() {
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "CHATTY"))))
                .isEqualTo(SupportRouting.TO_CHATTY);
    }

    // ---- fromResolveOrder ----

    @Test
    @DisplayName("Sin pedido resuelto se pregunta al usuario")
    void unresolvedOrderAsksUser() {
        var s = state(Map.of("intent", "TRACKING"));
        assertThat(SupportRouting.fromResolveOrder(s)).isEqualTo(SupportRouting.TO_ASK_USER);
    }

    @Test
    @DisplayName("Con pedido resuelto se continúa el flujo de la intención vigente")
    void resolvedOrderContinuesFlow() {
        assertThat(SupportRouting.fromResolveOrder(state(Map.of("intent", "TRACKING", "orderId", 123L))))
                .isEqualTo(SupportRouting.TO_TRACKING);
        assertThat(SupportRouting.fromResolveOrder(state(Map.of("intent", "CANCEL", "orderId", 200L))))
                .isEqualTo(SupportRouting.TO_CANCEL_CHECK);
        assertThat(SupportRouting.fromResolveOrder(state(Map.of("intent", "SUPPORT", "orderId", 300L))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
    }

    @Test
    @DisplayName("SUPPORT sin pedidos abre incidencia genérica; con varios pendientes de elegir, pregunta")
    void supportResolveOrderEdgeCases() {
        // Sin pedidos que identificar (awaiting NOTHING) → incidencia genérica, no se deja sin vía
        assertThat(SupportRouting.fromResolveOrder(state(Map.of("intent", "SUPPORT"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
        // Varios pedidos ofrecidos (ORDER_CHOICE): se pregunta sobre cuál es el problema
        assertThat(SupportRouting.fromResolveOrder(state(Map.of(
                "intent", "SUPPORT", "awaiting", "ORDER_CHOICE"))))
                .isEqualTo(SupportRouting.TO_ASK_USER);
    }

    // ---- fromCancelCheck ----

    @Test
    @DisplayName("Solo se ejecuta la cancelación con regla de negocio Y confirmación explícita")
    void cancelExecutionRequiresBothConditions() {
        assertThat(SupportRouting.fromCancelCheck(state(Map.of("cancellable", true, "confirmation", "YES"))))
                .isEqualTo(SupportRouting.TO_CANCEL_EXEC);
        assertThat(SupportRouting.fromCancelCheck(state(Map.of("cancellable", true, "confirmation", "NONE"))))
                .isEqualTo(SupportRouting.TO_ASK_USER);
        assertThat(SupportRouting.fromCancelCheck(state(Map.of("cancellable", true, "confirmation", "NO"))))
                .isEqualTo(SupportRouting.TO_ASK_USER);
        assertThat(SupportRouting.fromCancelCheck(state(Map.of("cancellable", false, "confirmation", "YES"))))
                .isEqualTo(SupportRouting.TO_ASK_USER);
    }
}
