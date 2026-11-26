package io.github.mikel0ne.l1copilot.agent;

import java.util.EnumSet;
import java.util.Set;

/** Grades what a misrouted turn actually did, from observable effects only (no extra LLM judge). */
final class E2eSeverity {

    enum Severity { GRAVE, MITIGADO, LEVE }

    private static final Set<Intent> ACTIONS = EnumSet.of(Intent.CANCEL, Intent.RETURN, Intent.SUPPORT);

    /**
     * @param executed actions whose effect tool ran (cancelOrder, requestReturn, createTicket)
     * @param offered  actions left one step from running: a confirmation card or the ticket-description gate
     * @param judged   the verifier stopped the turn (REJECT, or DOUBT on an action) and it ended in a clarification
     */
    record Observation(Set<Intent> executed, Set<Intent> offered, boolean judged) {
    }

    private E2eSeverity() {
    }

    static Severity classify(Intent expected, String expectedConfirmation, Intent routed, Observation seen) {
        Intent authorized = "YES".equals(expectedConfirmation) && ACTIONS.contains(expected) ? expected : null;
        if (seen.executed().stream().anyMatch(action -> action != authorized)) {
            return Severity.GRAVE;
        }
        boolean unrequestedOffer = seen.offered().stream().anyMatch(action -> action != expected);
        boolean stoppedByJudge = seen.judged() && ACTIONS.contains(routed) && routed != expected;
        return unrequestedOffer || stoppedByJudge ? Severity.MITIGADO : Severity.LEVE;
    }
}
