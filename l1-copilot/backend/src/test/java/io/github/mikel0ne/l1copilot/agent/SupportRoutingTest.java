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
        assertThat(SupportRouting.fromRouter(state(Map.of(SupportState.INTENT, "TRACKING"))))
                .isEqualTo(SupportRouting.TO_VERIFY);
        assertThat(SupportRouting.fromRouter(state(Map.of(SupportState.INTENT, "TRACKING", SupportState.ORDER_ID, 123L))))
                .isEqualTo(SupportRouting.TO_VERIFY);
    }

    @Test
    @DisplayName("TRACKING answering an order choice does NOT re-verify: goes straight through")
    void trackingMidFlowSkipsVerify() {
        assertThat(SupportRouting.fromRouter(state(Map.of(
                SupportState.INTENT, "TRACKING", SupportState.AWAITING, "ORDER_CHOICE"))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);
        assertThat(SupportRouting.fromRouter(state(Map.of(
                SupportState.INTENT, "TRACKING", SupportState.ORDER_ID, 123L, SupportState.AWAITING, "ORDER_CHOICE"))))
                .isEqualTo(SupportRouting.TO_TRACKING);
    }

    @Test
    @DisplayName("Fresh CANCEL goes through verify (with or without orderId)")
    void cancelFreshGoesToVerify() {
        assertThat(SupportRouting.fromRouter(state(Map.of(SupportState.INTENT, "CANCEL"))))
                .isEqualTo(SupportRouting.TO_VERIFY);
        assertThat(SupportRouting.fromRouter(state(Map.of(SupportState.INTENT, "CANCEL", SupportState.ORDER_ID, 200L))))
                .isEqualTo(SupportRouting.TO_VERIFY);
    }

    @Test
    @DisplayName("CANCEL answering a pending confirmation does NOT re-verify: goes straight through")
    void cancelMidFlowSkipsVerify() {
        assertThat(SupportRouting.fromRouter(state(Map.of(
                SupportState.INTENT, "CANCEL", SupportState.ORDER_ID, 200L, SupportState.AWAITING, "CANCEL_CONFIRM"))))
                .isEqualTo(SupportRouting.TO_CANCEL_CHECK);
    }

    @Test
    @DisplayName("SUPPORT does NOT go through verify: generic goes to the gate; on an unfocused order it identifies first")
    void supportRouting() {
        assertThat(SupportRouting.fromRouter(state(Map.of(SupportState.INTENT, "SUPPORT"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);

        assertThat(SupportRouting.fromRouter(state(Map.of(SupportState.INTENT, "SUPPORT", SupportState.ORDER_RELATED, true))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);

        assertThat(SupportRouting.fromRouter(state(Map.of(
                SupportState.INTENT, "SUPPORT", SupportState.ORDER_RELATED, true, SupportState.ORDER_ID, 300L))))
                .isEqualTo(SupportRouting.TO_SUPPORT);

        assertThat(SupportRouting.fromRouter(state(Map.of(SupportState.INTENT, "SUPPORT", SupportState.TICKET_DRAFT, "algo"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
        assertThat(SupportRouting.fromRouter(state(Map.of(SupportState.INTENT, "SUPPORT", SupportState.AWAITING, "TICKET_INFO"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
        assertThat(SupportRouting.fromRouter(state(Map.of(
                SupportState.INTENT, "SUPPORT", SupportState.ORDER_RELATED, true, SupportState.AWAITING, "TICKET_INFO"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
    }

    @Test
    @DisplayName("OTHER/NONE go to fallback")
    void fallbackRouting() {
        assertThat(SupportRouting.fromRouter(state(Map.of(SupportState.INTENT, "OTHER"))))
                .isEqualTo(SupportRouting.TO_FALLBACK);
        assertThat(SupportRouting.fromRouter(state(Map.of())))
                .isEqualTo(SupportRouting.TO_FALLBACK);
    }

    @Test
    @DisplayName("'Back to list' navigation does NOT re-verify: goes straight to resolve_order")
    void navigationSkipsVerify() {
        assertThat(SupportRouting.fromRouter(state(Map.of(
                SupportState.INTENT, "TRACKING", SupportState.NAVIGATING, true))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);
        assertThat(SupportRouting.fromRouter(state(Map.of(
                SupportState.INTENT, "CANCEL", SupportState.NAVIGATING, true))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);
    }

    @Test
    @DisplayName("REJECT sends to clarify, for reads and actions alike")
    void verifyRejectGoesToClarify() {
        assertThat(SupportRouting.fromVerify(state(Map.of(SupportState.INTENT, "SUPPORT", SupportState.VERDICT, "REJECT"))))
                .isEqualTo(SupportRouting.TO_CLARIFY);
        assertThat(SupportRouting.fromVerify(state(Map.of(SupportState.INTENT, "TRACKING", SupportState.VERDICT, "REJECT"))))
                .isEqualTo(SupportRouting.TO_CLARIFY);
    }

    @Test
    @DisplayName("DOUBT on an ACTION goes to clarify; on a READ it carries on (tentative tone)")
    void verifyDoubtDependsOnRisk() {
        assertThat(SupportRouting.fromVerify(state(Map.of(SupportState.INTENT, "SUPPORT", SupportState.VERDICT, "DOUBT"))))
                .isEqualTo(SupportRouting.TO_CLARIFY);
        assertThat(SupportRouting.fromVerify(state(Map.of(SupportState.INTENT, "CANCEL", SupportState.VERDICT, "DOUBT"))))
                .isEqualTo(SupportRouting.TO_CLARIFY);

        assertThat(SupportRouting.fromVerify(state(Map.of(SupportState.INTENT, "TRACKING", SupportState.VERDICT, "DOUBT"))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);
        assertThat(SupportRouting.fromVerify(state(Map.of(
                SupportState.INTENT, "TRACKING", SupportState.ORDER_ID, 123L, SupportState.VERDICT, "DOUBT"))))
                .isEqualTo(SupportRouting.TO_TRACKING);
    }

    @Test
    @DisplayName("SURE follows the normal flow of the request")
    void verifySureContinuesFlow() {
        assertThat(SupportRouting.fromVerify(state(Map.of(SupportState.INTENT, "SUPPORT", SupportState.VERDICT, "SURE"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
        assertThat(SupportRouting.fromVerify(state(Map.of(SupportState.INTENT, "CANCEL", SupportState.VERDICT, "SURE"))))
                .isEqualTo(SupportRouting.TO_RESOLVE_ORDER);
        assertThat(SupportRouting.fromVerify(state(Map.of(
                SupportState.INTENT, "CANCEL", SupportState.ORDER_ID, 200L, SupportState.VERDICT, "SURE"))))
                .isEqualTo(SupportRouting.TO_CANCEL_CHECK);
        assertThat(SupportRouting.fromVerify(state(Map.of(
                SupportState.INTENT, "TRACKING", SupportState.ORDER_ID, 123L, SupportState.VERDICT, "SURE"))))
                .isEqualTo(SupportRouting.TO_TRACKING);
    }

    @Test
    @DisplayName("An unrecognised LLM intent degrades to fallback and never breaks")
    void unknownIntentDegradesToFallback() {
        var s = state(Map.of(SupportState.INTENT, "BANANA"));
        assertThat(SupportRouting.fromRouter(s)).isEqualTo(SupportRouting.TO_FALLBACK);
    }

    @Test
    @DisplayName("FAQ goes to the FAQ skill")
    void faqRouting() {
        assertThat(SupportRouting.fromRouter(state(Map.of(SupportState.INTENT, "FAQ"))))
                .isEqualTo(SupportRouting.TO_FAQ);
    }

    @Test
    @DisplayName("TICKETS (ticket lookup) is a read: goes straight through, without verify")
    void ticketsRouting() {
        assertThat(SupportRouting.fromRouter(state(Map.of(SupportState.INTENT, "TICKETS"))))
                .isEqualTo(SupportRouting.TO_TICKETS);

        assertThat(SupportRouting.fromRouter(state(Map.of(SupportState.INTENT, "TICKETS", SupportState.AWAITING, "NOTHING"))))
                .isEqualTo(SupportRouting.TO_TICKETS);
    }

    @Test
    @DisplayName("CHATTY goes to small-talk mode")
    void chattyRouting() {
        assertThat(SupportRouting.fromRouter(state(Map.of(SupportState.INTENT, "CHATTY"))))
                .isEqualTo(SupportRouting.TO_CHATTY);
    }

    @Test
    @DisplayName("Without a resolved order, the user is asked")
    void unresolvedOrderAsksUser() {
        var s = state(Map.of(SupportState.INTENT, "TRACKING"));
        assertThat(SupportRouting.fromResolveOrder(s)).isEqualTo(SupportRouting.TO_ASK_USER);
    }

    @Test
    @DisplayName("With a resolved order, the current intent's flow continues")
    void resolvedOrderContinuesFlow() {
        assertThat(SupportRouting.fromResolveOrder(state(Map.of(SupportState.INTENT, "TRACKING", SupportState.ORDER_ID, 123L))))
                .isEqualTo(SupportRouting.TO_TRACKING);
        assertThat(SupportRouting.fromResolveOrder(state(Map.of(SupportState.INTENT, "CANCEL", SupportState.ORDER_ID, 200L))))
                .isEqualTo(SupportRouting.TO_CANCEL_CHECK);
        assertThat(SupportRouting.fromResolveOrder(state(Map.of(SupportState.INTENT, "SUPPORT", SupportState.ORDER_ID, 300L))))
                .isEqualTo(SupportRouting.TO_SUPPORT);
    }

    @Test
    @DisplayName("SUPPORT without orders opens a generic ticket; with several to choose from, it asks")
    void supportResolveOrderEdgeCases() {
        assertThat(SupportRouting.fromResolveOrder(state(Map.of(SupportState.INTENT, "SUPPORT"))))
                .isEqualTo(SupportRouting.TO_SUPPORT);

        assertThat(SupportRouting.fromResolveOrder(state(Map.of(
                SupportState.INTENT, "SUPPORT", SupportState.AWAITING, "ORDER_CHOICE"))))
                .isEqualTo(SupportRouting.TO_ASK_USER);
    }

    @Test
    @DisplayName("Cancellation only runs with the business rule AND explicit confirmation")
    void cancelExecutionRequiresBothConditions() {
        assertThat(SupportRouting.fromCancelCheck(state(Map.of(SupportState.CANCELLABLE, true, SupportState.CONFIRMATION, "YES"))))
                .isEqualTo(SupportRouting.TO_CANCEL_EXEC);
        assertThat(SupportRouting.fromCancelCheck(state(Map.of(SupportState.CANCELLABLE, true, SupportState.CONFIRMATION, "NONE"))))
                .isEqualTo(SupportRouting.TO_ASK_USER);
        assertThat(SupportRouting.fromCancelCheck(state(Map.of(SupportState.CANCELLABLE, true, SupportState.CONFIRMATION, "NO"))))
                .isEqualTo(SupportRouting.TO_ASK_USER);
        assertThat(SupportRouting.fromCancelCheck(state(Map.of(SupportState.CANCELLABLE, false, SupportState.CONFIRMATION, "YES"))))
                .isEqualTo(SupportRouting.TO_ASK_USER);
    }

    @Test
    @DisplayName("Accepting the action the system offered skips verify; a stale offer for another intent does not")
    void acceptedOfferSkipsVerify() {
        assertThat(SupportRouting.fromRouter(state(Map.of(
                SupportState.INTENT, "CANCEL", SupportState.ORDER_ID, 123L, SupportState.OFFERED_INTENT, "CANCEL"))))
                .isEqualTo(SupportRouting.TO_CANCEL_CHECK);
        assertThat(SupportRouting.fromRouter(state(Map.of(
                SupportState.INTENT, "CANCEL", SupportState.ORDER_ID, 123L, SupportState.OFFERED_INTENT, "RETURN"))))
                .isEqualTo(SupportRouting.TO_VERIFY);
        assertThat(SupportRouting.fromRouter(state(Map.of(
                SupportState.INTENT, "CANCEL", SupportState.ORDER_ID, 123L, SupportState.OFFERED_INTENT, "NONE"))))
                .isEqualTo(SupportRouting.TO_VERIFY);
    }
}
