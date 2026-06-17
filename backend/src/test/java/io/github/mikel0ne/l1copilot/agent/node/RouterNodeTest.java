package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.UiAction;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier.RouterDecision;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Lógica de foco del router, aislada del LLM (clasificador mockeado) y de Spring.
 * Aquí se garantiza que el pedido en curso es "pegajoso": no se salta de pedido
 * en pedido salvo elección explícita del menú, mención de otro id o vuelta al listado.
 */
class RouterNodeTest {

    private final IntentClassifier classifier = mock(IntentClassifier.class);
    private final RouterNode router = new RouterNode(classifier);

    private SupportState state(Map<String, Object> data) {
        return new SupportState(new HashMap<>(data));
    }

    private void decision(RouterDecision d) {
        when(classifier.classify(any(), any(), any(), any(), any())).thenReturn(d);
    }

    @Test
    @DisplayName("Foco pegajoso: con un pedido en curso y orderId=null, NO se cambia el pedido")
    void nullOrderKeepsFocus() {
        decision(new RouterDecision("SUPPORT", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of("orderId", 789L, "awaiting", "NOTHING")));

        // La intención puede cambiar (tracking → incidencia) pero el foco no se toca
        assertThat(updates).doesNotContainKey("orderId");
        assertThat(updates).containsEntry("intent", "SUPPORT");
    }

    @Test
    @DisplayName("Cambio explícito: si el usuario nombra OTRO pedido, se cambia el foco y se descarta el menú")
    void explicitDifferentOrderSwitchesFocus() {
        decision(new RouterDecision("TRACKING", 456L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of("orderId", 789L, "awaiting", "NOTHING")));

        assertThat(updates).containsEntry("orderId", 456L);
        assertThat(updates).containsEntry("candidates", AgentState.MARK_FOR_REMOVAL);
    }

    @Test
    @DisplayName("Elección del menú: en ORDER_CHOICE se fija el pedido elegido y se cierra la pregunta")
    void menuChoiceResolvesOrder() {
        decision(new RouterDecision("TRACKING", 789L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "awaiting", "ORDER_CHOICE",
                "candidates", List.of("#123 Portátil (IN_DELIVERY)", "#789 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry("orderId", 789L);
        assertThat(updates).containsEntry("awaiting", "NOTHING");
    }

    @Test
    @DisplayName("Volver al listado: BACK_TO_LIST suelta el foco, descarta el menú y reencamina al listado")
    void backToListReleasesFocus() {
        decision(new RouterDecision("OTHER", null, "NONE", "BACK_TO_LIST"));

        Map<String, Object> updates = router.apply(state(Map.of("orderId", 789L, "intent", "TRACKING")));

        assertThat(updates).containsEntry("orderId", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("candidates", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("awaiting", "NOTHING");
        // Sin orderId, la arista del router cae en resolve_order (vía TRACKING)
        assertThat(updates).containsEntry("intent", "TRACKING");
    }

    @Test
    @DisplayName("Volver al listado durante una cancelación conserva la intención CANCEL")
    void backToListKeepsCancelIntent() {
        decision(new RouterDecision("OTHER", null, "NONE", "BACK_TO_LIST"));

        Map<String, Object> updates = router.apply(state(Map.of("orderId", 789L, "intent", "CANCEL")));

        assertThat(updates).containsEntry("orderId", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("intent", "CANCEL");
    }

    @Test
    @DisplayName("Volver al menú principal: BACK_TO_MENU suelta el foco y cae siempre en OTHER (fallback)")
    void backToMenuReleasesFocusAndFallsBackToOther() {
        decision(new RouterDecision("TRACKING", null, "NONE", "BACK_TO_MENU"));

        Map<String, Object> updates = router.apply(state(Map.of("orderId", 789L, "intent", "TRACKING")));

        assertThat(updates).containsEntry("orderId", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("candidates", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("ticketDraft", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("awaiting", "NOTHING");
        // A diferencia de BACK_TO_LIST, siempre cae en OTHER → fallback (menú de capacidades)
        assertThat(updates).containsEntry("intent", "OTHER");
    }

    @Test
    @DisplayName("Digresión: una FAQ con gestión pendiente suspende la tarea SIN tocar su contexto")
    void faqDuringPendingTaskSuspendsWithoutTouchingContext() {
        decision(new RouterDecision("FAQ", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "intent", "TRACKING",
                "awaiting", "ORDER_CHOICE",
                "candidates", List.of("#123 Portátil (IN_DELIVERY)", "#456 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry("intent", "FAQ");
        // Se recuerda la gestión para retomarla; el contexto de la tarea queda intacto
        assertThat(updates).containsEntry("suspendedIntent", "TRACKING");
        assertThat(updates).doesNotContainKey("orderId");
        assertThat(updates).doesNotContainKey("candidates");
        assertThat(updates).doesNotContainKey("awaiting");
    }

    @Test
    @DisplayName("Digresión: una FAQ sin gestión pendiente NO suspende nada (FAQ normal)")
    void faqWithoutPendingTaskIsNotDigression() {
        decision(new RouterDecision("FAQ", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of("awaiting", "NOTHING")));

        assertThat(updates).containsEntry("intent", "FAQ");
        assertThat(updates).doesNotContainKey("suspendedIntent");
    }

    @Test
    @DisplayName("Retorno: al retomar la gestión tras la FAQ se restaura la intención y se descarta la suspensión")
    void resumingAfterDigressionClearsSuspendedIntent() {
        // El usuario, tras la FAQ, elige por fin el pedido: el LLM —que vio TRACKING como
        // vigente— devuelve TRACKING + orderId; el router cierra la digresión.
        decision(new RouterDecision("TRACKING", 123L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "intent", "FAQ",
                "suspendedIntent", "TRACKING",
                "awaiting", "ORDER_CHOICE",
                "candidates", List.of("#123 Portátil (IN_DELIVERY)", "#456 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry("suspendedIntent", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("intent", "TRACKING");
        assertThat(updates).containsEntry("orderId", 123L);
        assertThat(updates).containsEntry("awaiting", "NOTHING");
    }

    @Test
    @DisplayName("Botón confirmar: el comando se resuelve a YES de forma determinista, sin LLM")
    void confirmCancelCommandIsDeterministic() {
        Map<String, Object> updates = router.apply(state(Map.of(
                "userMessage", UiAction.confirmCancel(200L),
                "awaiting", "CANCEL_CONFIRM",
                "orderId", 200L)));

        assertThat(updates).containsEntry("intent", "CANCEL");
        assertThat(updates).containsEntry("orderId", 200L);
        assertThat(updates).containsEntry("confirmation", "YES");
        verifyNoInteractions(classifier);
    }

    @Test
    @DisplayName("Botón descartar: el comando se resuelve a NO de forma determinista, sin LLM")
    void abortCancelCommandIsDeterministic() {
        Map<String, Object> updates = router.apply(state(Map.of(
                "userMessage", UiAction.abortCancel(200L),
                "awaiting", "CANCEL_CONFIRM",
                "orderId", 200L)));

        assertThat(updates).containsEntry("confirmation", "NO");
        assertThat(updates).containsEntry("orderId", 200L);
        verifyNoInteractions(classifier);
    }

    @Test
    @DisplayName("Selección de tarjeta '#id': fija el foco sin LLM si el id está en el menú")
    void cardSelectionResolvesFocusDeterministically() {
        Map<String, Object> updates = router.apply(state(Map.of(
                "userMessage", "#123",
                "awaiting", "ORDER_CHOICE",
                "candidates", List.of("#123 Portátil (IN_DELIVERY)", "#789 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry("orderId", 123L);
        assertThat(updates).containsEntry("awaiting", "NOTHING");
        verifyNoInteractions(classifier);
    }

    @Test
    @DisplayName("Digresión chatty: la charla informal en mitad de una gestión la suspende igual que FAQ")
    void chattyDuringPendingTaskSuspends() {
        decision(new RouterDecision("CHATTY", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "intent", "TRACKING",
                "awaiting", "ORDER_CHOICE",
                "candidates", List.of("#123 Portátil (IN_DELIVERY)"))));

        assertThat(updates).containsEntry("intent", "CHATTY");
        assertThat(updates).containsEntry("suspendedIntent", "TRACKING");
        assertThat(updates).doesNotContainKey("orderId");
        assertThat(updates).doesNotContainKey("awaiting");
    }

    @Test
    @DisplayName("Aclaración: un OTHER ambiguo con foco incrementa el contador; sin foco lo reinicia")
    void clarifyAttemptsTrackOnlyWithContext() {
        decision(new RouterDecision("OTHER", null, "NONE", "NONE"));

        Map<String, Object> withFocus = router.apply(state(Map.of(
                "intent", "TRACKING", "orderId", 789L, "clarifyAttempts", 1)));
        assertThat(withFocus).containsEntry("clarifyAttempts", 2);

        Map<String, Object> noFocus = router.apply(state(Map.of("clarifyAttempts", 3)));
        assertThat(noFocus).containsEntry("clarifyAttempts", 0);
    }

    @Test
    @DisplayName("Gate de incidencia: si el usuario sigue en SUPPORT, NO se descarta el borrador")
    void ticketGateKeepsDraftWhenStillSupport() {
        decision(new RouterDecision("SUPPORT", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "intent", "SUPPORT", "awaiting", "TICKET_CONFIRM", "ticketDraft", "el monitor llegó roto")));

        assertThat(updates).containsEntry("intent", "SUPPORT");
        // El borrador se conserva y el gate sigue en pie: lo gestiona el nodo SUPPORT
        assertThat(updates).doesNotContainKey("ticketDraft");
        assertThat(updates).doesNotContainEntry("awaiting", "NOTHING");
    }

    @Test
    @DisplayName("Gate de incidencia: si el usuario pide otra cosa, se descarta el borrador y se reencamina")
    void ticketGateAbandonedWhenUserWantsSomethingElse() {
        decision(new RouterDecision("OTHER", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "intent", "SUPPORT", "awaiting", "TICKET_CONFIRM", "ticketDraft", "tengo un problema")));

        assertThat(updates).containsEntry("ticketDraft", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("awaiting", "NOTHING");
        assertThat(updates).containsEntry("intent", "OTHER");
        // No escala a modo aclaración: el contador se reinicia al abandonar el gate
        assertThat(updates).containsEntry("clarifyAttempts", 0);
    }

    @Test
    @DisplayName("Gate de incidencia: pivotar a cancelar otro pedido descarta el borrador y cambia el foco")
    void ticketGatePivotToCancelDropsDraftAndSwitchesOrder() {
        decision(new RouterDecision("CANCEL", 200L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "intent", "SUPPORT", "awaiting", "TICKET_CONFIRM", "ticketDraft", "esto va fatal")));

        assertThat(updates).containsEntry("ticketDraft", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("awaiting", "NOTHING");
        assertThat(updates).containsEntry("intent", "CANCEL");
        assertThat(updates).containsEntry("orderId", 200L);
    }

    @Test
    @DisplayName("Gate de incidencia: una FAQ lateral lo SUSPENDE (no lo abandona): el borrador sobrevive")
    void ticketGateFaqDigressionSuspendsWithoutDroppingDraft() {
        decision(new RouterDecision("FAQ", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "intent", "SUPPORT", "awaiting", "TICKET_CONFIRM", "ticketDraft", "el monitor llegó roto")));

        assertThat(updates).containsEntry("intent", "FAQ");
        assertThat(updates).containsEntry("suspendedIntent", "SUPPORT");
        // La digresión no destruye el contexto de la incidencia: el borrador se conserva
        assertThat(updates).doesNotContainKey("ticketDraft");
    }

    @Test
    @DisplayName("'#id' ajeno al menú no es atajo: lo resuelve el LLM (no se fija a ciegas)")
    void unknownCardIdFallsThroughToLlm() {
        decision(new RouterDecision("TRACKING", null, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "userMessage", "#999",
                "awaiting", "ORDER_CHOICE",
                "candidates", List.of("#123 Portátil (IN_DELIVERY)"))));

        // No hay atajo determinista: no se fija el #999; el flujo normal del LLM decide
        assertThat(updates).doesNotContainEntry("orderId", 999L);
    }
}
