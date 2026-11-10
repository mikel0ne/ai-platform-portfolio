package io.github.mikel0ne.l1copilot.agent;

public final class SupportRouting {

    public static final String TO_RESOLVE_ORDER = "resolve_order";
    public static final String TO_TRACKING = "tracking";
    public static final String TO_CANCEL_CHECK = "cancel_check";
    public static final String TO_CANCEL_EXEC = "cancel_exec";
    public static final String TO_RETURN_CHECK = "return_check";
    public static final String TO_RETURN_EXEC = "return_exec";
    public static final String TO_SUPPORT = "support";
    public static final String TO_TICKETS = "tickets";
    public static final String TO_FAQ = "faq";
    public static final String TO_CHATTY = "chatty";
    public static final String TO_FALLBACK = "fallback";
    public static final String TO_ASK_USER = "ask_user";
    public static final String TO_VERIFY = "verify";
    public static final String TO_CLARIFY = "clarify";

    private SupportRouting() {
    }

    public static String fromRouter(SupportState state) {
        return switch (state.intent()) {
            case TRACKING -> needsVerify(state)
                    ? TO_VERIFY
                    : (state.orderId().isPresent() ? TO_TRACKING : TO_RESOLVE_ORDER);
            case CANCEL -> needsVerify(state)
                    ? TO_VERIFY
                    : (state.orderId().isPresent() ? TO_CANCEL_CHECK : TO_RESOLVE_ORDER);
            case RETURN -> needsVerify(state)
                    ? TO_VERIFY
                    : (state.orderId().isPresent() ? TO_RETURN_CHECK : TO_RESOLVE_ORDER);

            case SUPPORT -> supportNeedsOrder(state) ? TO_RESOLVE_ORDER : TO_SUPPORT;
            case TICKETS -> TO_TICKETS;
            case FAQ -> TO_FAQ;
            case CHATTY -> TO_CHATTY;
            default -> TO_FALLBACK;
        };
    }

    private static boolean needsVerify(SupportState state) {
        return state.awaiting() == Awaiting.NOTHING
                && state.ticketDraft().isBlank()
                && !state.navigating();
    }

    private static boolean supportNeedsOrder(SupportState state) {
        return state.intent() == Intent.SUPPORT
                && state.orderRelated()
                && state.orderId().isEmpty()
                && state.awaiting() == Awaiting.NOTHING
                && state.ticketDraft().isBlank();
    }

    public static String fromVerify(SupportState state) {
        Verdict verdict = state.verdict();
        Intent intent = state.intent();

        if (!proceedsAfterVerify(verdict, intent)) {
            return TO_CLARIFY;
        }
        return switch (intent) {
            case TRACKING -> state.orderId().isPresent() ? TO_TRACKING : TO_RESOLVE_ORDER;
            case CANCEL -> state.orderId().isPresent() ? TO_CANCEL_CHECK : TO_RESOLVE_ORDER;
            case RETURN -> state.orderId().isPresent() ? TO_RETURN_CHECK : TO_RESOLVE_ORDER;
            case SUPPORT -> TO_SUPPORT;
            default -> TO_FALLBACK;
        };
    }

    public static boolean proceedsAfterVerify(Verdict verdict, Intent intent) {
        boolean action = intent == Intent.CANCEL || intent == Intent.RETURN || intent == Intent.SUPPORT;
        return !(verdict == Verdict.REJECT || (verdict == Verdict.DOUBT && action));
    }

    public static String fromResolveOrder(SupportState state) {
        if (state.orderId().isEmpty()) {
            if (state.intent() == Intent.SUPPORT && state.awaiting() == Awaiting.NOTHING) {
                return TO_SUPPORT;
            }
            return TO_ASK_USER;
        }
        return switch (state.intent()) {
            case CANCEL -> TO_CANCEL_CHECK;
            case RETURN -> TO_RETURN_CHECK;
            case SUPPORT -> TO_SUPPORT;
            default -> TO_TRACKING;
        };
    }

    public static String fromCancelCheck(SupportState state) {
        return state.cancellable() && state.confirmation() == Confirmation.YES
                ? TO_CANCEL_EXEC
                : TO_ASK_USER;
    }

    public static String fromReturnCheck(SupportState state) {
        return state.returnable() && state.confirmation() == Confirmation.YES
                ? TO_RETURN_EXEC
                : TO_ASK_USER;
    }
}
