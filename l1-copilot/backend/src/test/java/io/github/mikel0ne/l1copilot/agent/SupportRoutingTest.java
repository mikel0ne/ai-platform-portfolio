package io.github.mikel0ne.l1copilot.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SupportRoutingTest {

    private SupportState state(Map<String, Object> data) {
        return new SupportState(new HashMap<>(data));
    }

    @Test
    @DisplayName("Fresh TRACKING goes through verify (with or without orderId)")
    void trackingFreshGoesToVerify() {
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "TRACKING"))))
                .isEqualTo(SupportRouting.TO_VERIFY);
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "TRACKING", "orderId", 123L))))
                .isEqualTo(SupportRouting.TO_VERIFY);
    }

    @Test
    @DisplayName("TRACKING answering an order choice does NOT re-verify: goes straight through")
    void trackingMidFlowSkipsVerify() {
        assertThat(SupportRouting.fromRouter(state(Map.of(
                "intent", "TRACKING", "awaiting", "ORDER_CHOICE"))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);
        assertThat(SupportRouting.fromRouter(state(Map.of(
                "intent", "TRACKING", "orderId", 123L, "awaiting", "ORDER_CHOICE"))))
                .isEqualTo(SupportRouting.TO_TRACKING);
    }

    @Test
    @DisplayName("Fresh CANCEL goes through verify (with or without orderId)")
    void cancelFreshGoesToVerify() {
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "CANCEL"))))
                .isEqualTo(SupportRouting.TO_VERIFY);
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "CANCEL", "orderId", 200L))))
                .isEqualTo(SupportRouting.TO_VERIFY);
    }

    @Test
    @DisplayName("CANCEL answering a pending confirmation does NOT re-verify: goes straight through")
    void cancelMidFlowSkipsVerify() {
        assertThat(SupportRouting.fromRouter(state(Map.of(
                "intent", "CANCEL", "orderId", 200L, "awaiting", "CANCEL_CONFIRM"))))
                .isEqualTo(SupportRouting.TO_CANCEL_CHECK);
    }

    @Test
    @DisplayName("SUPPORT does NOT go through verify: generic goes to the gate; on an unfocused order it identifies first")
    void supportRouting() {
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "SUPPORT"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);

        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "SUPPORT", "orderRelated", true))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);

        assertThat(SupportRouting.fromRouter(state(Map.of(
                "intent", "SUPPORT", "orderRelated", true, "orderId", 300L))))
                .isEqualTo(SupportRouting.TO_SUPPORT);

        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "SUPPORT", "ticketDraft", "algo"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "SUPPORT", "awaiting", "TICKET_CONFIRM"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
        assertThat(SupportRouting.fromRouter(state(Map.of(
                "intent", "SUPPORT", "orderRelated", true, "awaiting", "TICKET_CONFIRM"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
    }

    @Test
    @DisplayName("OTHER/NONE go to fallback")
    void fallbackRouting() {
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "OTHER"))))
                .isEqualTo(SupportRouting.TO_FALLBACK);
        assertThat(SupportRouting.fromRouter(state(Map.of())))
                .isEqualTo(SupportRouting.TO_FALLBACK);
    }

    @Test
    @DisplayName("'Back to list' navigation does NOT re-verify: goes straight to resolve_order")
    void navigationSkipsVerify() {
        assertThat(SupportRouting.fromRouter(state(Map.of(
                "intent", "TRACKING", "navigating", true))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);
        assertThat(SupportRouting.fromRouter(state(Map.of(
                "intent", "CANCEL", "navigating", true))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);
    }

    @Test
    @DisplayName("REJECT sends to clarify, for reads and actions alike")
    void verifyRejectGoesToClarify() {
        assertThat(SupportRouting.fromVerify(state(Map.of("intent", "SUPPORT", "verdict", "REJECT"))))
                .isEqualTo(SupportRouting.TO_CLARIFY);
        assertThat(SupportRouting.fromVerify(state(Map.of("intent", "TRACKING", "verdict", "REJECT"))))
                .isEqualTo(SupportRouting.TO_CLARIFY);
    }

    @Test
    @DisplayName("DOUBT on an ACTION goes to clarify; on a READ it carries on (tentative tone)")
    void verifyDoubtDependsOnRisk() {
        assertThat(SupportRouting.fromVerify(state(Map.of("intent", "SUPPORT", "verdict", "DOUBT"))))
                .isEqualTo(SupportRouting.TO_CLARIFY);
        assertThat(SupportRouting.fromVerify(state(Map.of("intent", "CANCEL", "verdict", "DOUBT"))))
                .isEqualTo(SupportRouting.TO_CLARIFY);

        assertThat(SupportRouting.fromVerify(state(Map.of("intent", "TRACKING", "verdict", "DOUBT"))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);
        assertThat(SupportRouting.fromVerify(state(Map.of(
                "intent", "TRACKING", "orderId", 123L, "verdict", "DOUBT"))))
                .isEqualTo(SupportRouting.TO_TRACKING);
    }

    @Test
    @DisplayName("SURE follows the normal flow of the request")
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
    @DisplayName("An unrecognised LLM intent degrades to fallback and never breaks")
    void unknownIntentDegradesToFallback() {
        var s = state(Map.of("intent", "BANANA"));
        assertThat(SupportRouting.fromRouter(s)).isEqualTo(SupportRouting.TO_FALLBACK);
    }

    @Test
    @DisplayName("FAQ goes to the FAQ skill")
    void faqRouting() {
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "FAQ"))))
                .isEqualTo(SupportRouting.TO_FAQ);
    }

    @Test
    @DisplayName("TICKETS (ticket lookup) is a read: goes straight through, without verify")
    void ticketsRouting() {
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "TICKETS"))))
                .isEqualTo(SupportRouting.TO_TICKETS);

        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "TICKETS", "awaiting", "NOTHING"))))
                .isEqualTo(SupportRouting.TO_TICKETS);
    }

    @Test
    @DisplayName("CHATTY goes to small-talk mode")
    void chattyRouting() {
        assertThat(SupportRouting.fromRouter(state(Map.of("intent", "CHATTY"))))
                .isEqualTo(SupportRouting.TO_CHATTY);
    }

    @Test
    @DisplayName("Without a resolved order, the user is asked")
    void unresolvedOrderAsksUser() {
        var s = state(Map.of("intent", "TRACKING"));
        assertThat(SupportRouting.fromResolveOrder(s)).isEqualTo(SupportRouting.TO_ASK_USER);
    }

    @Test
    @DisplayName("With a resolved order, the current intent's flow continues")
    void resolvedOrderContinuesFlow() {
        assertThat(SupportRouting.fromResolveOrder(state(Map.of("intent", "TRACKING", "orderId", 123L))))
                .isEqualTo(SupportRouting.TO_TRACKING);
        assertThat(SupportRouting.fromResolveOrder(state(Map.of("intent", "CANCEL", "orderId", 200L))))
                .isEqualTo(SupportRouting.TO_CANCEL_CHECK);
        assertThat(SupportRouting.fromResolveOrder(state(Map.of("intent", "SUPPORT", "orderId", 300L))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
    }

    @Test
    @DisplayName("SUPPORT without orders opens a generic ticket; with several to choose from, it asks")
    void supportResolveOrderEdgeCases() {
        assertThat(SupportRouting.fromResolveOrder(state(Map.of("intent", "SUPPORT"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);

        assertThat(SupportRouting.fromResolveOrder(state(Map.of(
                "intent", "SUPPORT", "awaiting", "ORDER_CHOICE"))))
                .isEqualTo(SupportRouting.TO_ASK_USER);
    }

    @Test
    @DisplayName("Cancellation only runs with the business rule AND explicit confirmation")
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
