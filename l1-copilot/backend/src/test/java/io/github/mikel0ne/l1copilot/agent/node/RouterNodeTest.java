package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.UiAction;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier;
import io.github.mikel0ne.l1copilot.agent.llm.IntentClassifier.RouterDecision;
import io.github.mikel0ne.l1copilot.config.ConversationHistoryProperties;
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
    private final RouterNode router = new RouterNode(classifier, new ConversationHistoryProperties(null, null));

    private SupportState state(Map<String, Object> data) {
        return new SupportState(new HashMap<>(data));
    }

    private void decision(RouterDecision d) {
        when(classifier.classify(any(), any(), any(), any(), any(), any())).thenReturn(d);
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
    @DisplayName("Cambio explícito: si el usuario nombra OTRO pedido (gestión fresca), se PROPONE el foco (lo confirma verify)")
    void explicitDifferentOrderSwitchesFocus() {
        decision(new RouterDecision("TRACKING", 456L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of("orderId", 789L, "awaiting", "NOTHING")));

        // No se compromete el foco antes de validar: el router lo propone y verify lo ascenderá
        // (y descartará el menú) solo si aprueba la acción. El foco real sigue intacto aquí.
        assertThat(updates).containsEntry("proposedOrderId", 456L);
        assertThat(updates).doesNotContainKey("orderId");
        assertThat(updates).doesNotContainKey("candidates");
    }

    @Test
    @DisplayName("Elección del menú: en ORDER_CHOICE se propone el pedido elegido y se cierra la pregunta (verify lo confirma)")
    void menuChoiceResolvesOrder() {
        decision(new RouterDecision("TRACKING", 789L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "awaiting", "ORDER_CHOICE",
                "candidates", List.of("#123 Portátil (IN_DELIVERY)", "#789 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry("proposedOrderId", 789L);
        assertThat(updates).doesNotContainKey("orderId");
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
    @DisplayName("'atrás' (BACK) con un pedido en foco: sube un nivel al listado")
    void backWithFocusGoesToList() {
        // "atrás", "volver", "anterior"... no los clasifica una lista de palabras: el LLM emite
        // BACK (subir un nivel) y el router decide el destino según el nivel real. Con foco → listado.
        decision(new RouterDecision("TRACKING", null, "NONE", "BACK"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "userMessage", "atrás",
                "orderId", 789L,
                "intent", "TRACKING")));

        assertThat(updates).containsEntry("orderId", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("awaiting", "NOTHING");
        // Con foco, BACK sube al listado (vía TRACKING → resolve_order)
        assertThat(updates).containsEntry("intent", "TRACKING");
    }

    @Test
    @DisplayName("'atrás' (BACK) estando ya en el listado (sin foco): sube al menú, NO re-lista")
    void backOnListWithoutFocusGoesToMenu() {
        // El usuario está en el listado eligiendo (ORDER_CHOICE, hay candidatos pero NINGÚN foco).
        // Un "atrás" aquí debe SUBIR al menú, no volver a pintar el mismo listado (el bucle que veía
        // el usuario). Lo decide el router por el nivel: sin foco → BACK_TO_MENU → fallback.
        decision(new RouterDecision("OTHER", null, "NONE", "BACK"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "userMessage", "atrás",
                "awaiting", "ORDER_CHOICE",
                "intent", "TRACKING",
                "candidates", List.of("#123 Portátil (IN_DELIVERY)", "#789 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry("orderId", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("candidates", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("awaiting", "NOTHING");
        // Sin foco, BACK cae en OTHER → fallback (menú de capacidades), no en resolve_order
        assertThat(updates).containsEntry("intent", "OTHER");
    }

    @Test
    @DisplayName("'atrás' (BACK) iniciando una devolución con el foco diferido sin comprometer: vuelve al listado, NO al menú")
    void backWithDeferredFocusDroppedGoesToList() {
        // Caso del foco diferido: el usuario eligió un pedido del listado para devolverlo, pero
        // verify dudó y mandó a clarify SIN ascender el foco; proposedOrderId es efímero, así que
        // este turno NO hay orderId. El listado sigue ahí (candidates) y ya no estamos eligiendo
        // (awaiting != ORDER_CHOICE): "atrás" debe volver al LISTADO, no saltarse un nivel al menú.
        decision(new RouterDecision("OTHER", null, "NONE", "BACK"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "userMessage", "atrás",
                "awaiting", "NOTHING",
                "intent", "RETURN",
                "candidates", List.of("#123 Portátil (DELIVERED)", "#789 Monitor (DELIVERED)"))));

        assertThat(updates).containsEntry("orderId", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("candidates", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("awaiting", "NOTHING");
        // BACK_TO_LIST conserva la gestión (RETURN) para que, con orderId vacío, la arista del
        // router caiga en resolve_order y re-ofrezca el listado, no en fallback (menú).
        assertThat(updates).containsEntry("intent", "RETURN");
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
        // Gestión fresca tras la digresión: el foco se PROPONE (lo confirma verify), no se fija ya
        assertThat(updates).containsEntry("proposedOrderId", 123L);
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
    @DisplayName("SUPPORT: el slot orderRelated se vuelca al estado (gobierna si se identifica el pedido)")
    void supportOrderRelatedIsDumped() {
        decision(new RouterDecision("SUPPORT", null, "NONE", "NONE", true));
        assertThat(router.apply(state(Map.of())))
                .containsEntry("intent", "SUPPORT")
                .containsEntry("orderRelated", true);

        // Incidencia genérica: orderRelated=false
        decision(new RouterDecision("SUPPORT", null, "NONE", "NONE", false));
        assertThat(router.apply(state(Map.of())))
                .containsEntry("orderRelated", false);
    }

    @Test
    @DisplayName("orderRelated solo se vuelca en SUPPORT; otras intenciones no lo tocan")
    void orderRelatedOnlyForSupport() {
        decision(new RouterDecision("TRACKING", 123L, "NONE", "NONE", true));
        assertThat(router.apply(state(Map.of()))).doesNotContainKey("orderRelated");
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
    @DisplayName("Gate de incidencia: pivotar a cancelar otro pedido descarta el borrador y PROPONE el foco (verify lo confirma)")
    void ticketGatePivotToCancelDropsDraftAndSwitchesOrder() {
        decision(new RouterDecision("CANCEL", 200L, "NONE", "NONE"));

        Map<String, Object> updates = router.apply(state(Map.of(
                "intent", "SUPPORT", "awaiting", "TICKET_CONFIRM", "ticketDraft", "esto va fatal")));

        assertThat(updates).containsEntry("ticketDraft", AgentState.MARK_FOR_REMOVAL);
        assertThat(updates).containsEntry("awaiting", "NOTHING");
        assertThat(updates).containsEntry("intent", "CANCEL");
        // Al descartar el borrador el turno queda como una cancelación FRESCA (awaiting NOTHING,
        // ticket vacío): pasa por verify, así que el foco se PROPONE y lo asciende verify si aprueba.
        assertThat(updates).containsEntry("proposedOrderId", 200L);
        assertThat(updates).doesNotContainKey("orderId");
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
