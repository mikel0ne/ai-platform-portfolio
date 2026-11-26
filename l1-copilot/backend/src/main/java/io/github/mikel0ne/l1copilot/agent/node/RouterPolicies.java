package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.Confirmation;
import io.github.mikel0ne.l1copilot.agent.Intent;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class RouterPolicies {

    private RouterPolicies() {
    }

    static boolean suspendForDigression(RouterTurn t) {
        if (!t.isDigression() || t.state().awaiting() == Awaiting.NOTHING) {
            return false;
        }
        Intent suspended = t.state().suspendedIntent().orElse(t.state().intent());
        t.setIntent(t.intent());
        t.suspend(suspended);
        t.setClarifyAttempts(0);
        log.debug("---ROUTER--- digression {}: suspending request {} (awaiting={})",
                t.intent(), suspended, t.state().awaiting());
        return true;
    }

    static void resumeAfterDigression(RouterTurn t) {
        if (t.state().suspendedIntent().isPresent() && !t.isDigression()) {
            t.clearSuspendedIntent();
            log.debug("---ROUTER--- digression over: resuming the request (intent={})", t.intent());
        }
    }

    static void recordIntent(RouterTurn t) {
        if (t.intent() != Intent.NONE) {
            t.setIntent(t.intent());
        }
        if (t.intent() == Intent.SUPPORT) {
            t.setOrderRelated(t.decision().orderRelated());
        }
    }

    static void dropTicketDraft(RouterTurn t) {
        if (t.abandonsTicketGate()) {
            t.dropTicketDraft();
            t.setAwaiting(Awaiting.NOTHING);
            log.debug("---ROUTER--- ticket dropped: the user asks for something else (intent={})", t.intent());
        }

        // A draft left by a doubtful SUPPORT only survives the turn that accepts it; otherwise it would skip
        // the verifier of every later action and become the start of an unrequested ticket.
        if (t.leavesStaleTicketDraft()) {
            t.dropTicketDraft();
            log.debug("---ROUTER--- stale ticket draft dropped (intent={})", t.intent());
        }
    }

    static void moveFocus(RouterTurn t) {
        Long orderId = t.decision().orderId();
        if (orderId == null || orderId <= 0 || t.isInformational()) {
            return;
        }
        long newFocus = orderId;
        boolean selecting = t.state().awaiting() == Awaiting.ORDER_CHOICE;
        boolean differentOrder = t.state().orderId().filter(id -> id == newFocus).isEmpty();
        if (selecting) {
            t.setAwaiting(Awaiting.NOTHING);
        }

        if (routesToVerify(t)) {
            t.proposeFocus(newFocus);
        } else {
            t.setFocus(newFocus);
            if (!selecting && differentOrder) {
                t.dropCandidates();
            }
        }
    }

    static void bindConfirmation(RouterTurn t) {
        Awaiting awaiting = t.state().awaiting();
        if (awaiting != Awaiting.CANCEL_CONFIRM && awaiting != Awaiting.RETURN_CONFIRM) {
            return;
        }
        Confirmation confirmation = Confirmation.fromState(t.decision().confirmation());
        if (confirmation == Confirmation.YES && !confirmsPendingAction(t)) {
            log.debug("---ROUTER--- YES ignored: it does not match the pending {} of order {}",
                    awaiting, t.state().orderId().orElse(null));
            confirmation = Confirmation.NONE;
        }
        t.setConfirmation(confirmation);
    }

    static void countClarifyAttempts(RouterTurn t) {
        boolean ongoing = t.state().orderId().isPresent() || t.state().awaiting() != Awaiting.NOTHING;
        if (t.intent() == Intent.OTHER && ongoing && !t.abandonsTicketGate()) {
            t.setClarifyAttempts(t.state().clarifyAttempts() + 1);
        } else {
            t.setClarifyAttempts(0);
        }
    }

    static boolean routesToVerify(RouterTurn t) {
        Intent intent = t.intent();
        boolean verifiable = intent == Intent.TRACKING || intent == Intent.CANCEL
                || intent == Intent.RETURN;
        return verifiable && t.effectiveAwaiting() == Awaiting.NOTHING && t.effectiveTicketDraftBlank();
    }

    // A typed YES only executes the action and order the confirmation card was shown for.
    static boolean confirmsPendingAction(RouterTurn t) {
        Intent pendingAction = switch (t.state().awaiting()) {
            case CANCEL_CONFIRM -> Intent.CANCEL;
            case RETURN_CONFIRM -> Intent.RETURN;
            default -> Intent.NONE;
        };
        Intent effectiveIntent = t.intent() == Intent.NONE ? t.state().intent() : t.intent();
        return effectiveIntent == pendingAction
                && t.effectiveOrderId().equals(t.state().orderId())
                && !t.focusProposed();
    }
}
