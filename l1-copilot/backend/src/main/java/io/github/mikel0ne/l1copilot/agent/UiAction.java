package io.github.mikel0ne.l1copilot.agent;

import java.util.Optional;

public final class UiAction {

    public static final String CONFIRM_CANCEL = "action:confirm-cancel:";

    public static final String ABORT_CANCEL = "action:abort-cancel:";

    public static final String CONFIRM_RETURN = "action:confirm-return:";

    public static final String ABORT_RETURN = "action:abort-return:";

    private UiAction() {
    }

    public static String confirmCancel(long orderId) {
        return CONFIRM_CANCEL + orderId;
    }

    public static String abortCancel(long orderId) {
        return ABORT_CANCEL + orderId;
    }

    public static String confirmReturn(long orderId) {
        return CONFIRM_RETURN + orderId;
    }

    public static String abortReturn(long orderId) {
        return ABORT_RETURN + orderId;
    }

    public static Optional<Long> orderIdOf(String message, String prefix) {
        if (message == null) {
            return Optional.empty();
        }
        String trimmed = message.trim();
        if (!trimmed.startsWith(prefix)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(trimmed.substring(prefix.length()).trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static Optional<Long> bareOrderId(String message) {
        if (message == null) {
            return Optional.empty();
        }
        String trimmed = message.trim();
        if (!trimmed.matches("#\\d+")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(trimmed.substring(1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
