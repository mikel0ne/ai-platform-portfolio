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
    @DisplayName("TRACKING sin orderId va a desambiguación")
    void trackingWithoutOrderGoesToResolve() {
        var s = state(Map.of("intent", "TRACKING"));
        assertThat(SupportRouting.fromRouter(s)).isEqualTo(SupportRouting.TO_RESOLVE_ORDER);
    }

    @Test
    @DisplayName("TRACKING con orderId va directo a tracking")
    void trackingWithOrderGoesToTracking() {
        var s = state(Map.of("intent", "TRACKING", "orderId", 123L));
        assertThat(SupportRouting.fromRouter(s)).isEqualTo(SupportRouting.TO_TRACKING);
    }

    @Test
    @DisplayName("CANCEL sin orderId va a desambiguación; con orderId a la comprobación")
    void cancelRouting() {
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "CANCEL"))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "CANCEL", "orderId", 200L))))
                .isEqualTo(SupportRouting.TO_CANCEL_CHECK);
    }

    @Test
    @DisplayName("SUPPORT va al flujo de incidencias y OTHER/NONE al fallback")
    void supportAndFallbackRouting() {
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "SUPPORT"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "OTHER"))))
                .isEqualTo(SupportRouting.TO_FALLBACK);
        assertThat(SupportRouting.fromRouter(state(Map.of())))
                .isEqualTo(SupportRouting.TO_FALLBACK);
    }

    @Test
    @DisplayName("Una intención no reconocida del LLM degrada a fallback, nunca rompe")
    void unknownIntentDegradesToFallback() {
        var s = state(Map.of("intent", "BANANA"));
        assertThat(SupportRouting.fromRouter(s)).isEqualTo(SupportRouting.TO_FALLBACK);
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
