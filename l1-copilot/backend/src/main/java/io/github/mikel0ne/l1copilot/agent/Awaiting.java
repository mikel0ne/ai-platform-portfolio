package io.github.mikel0ne.l1copilot.agent;

public enum Awaiting {

    NOTHING,

    ORDER_CHOICE,

    CANCEL_CONFIRM,

    RETURN_CONFIRM,

    TICKET_INFO;

    public static Awaiting fromState(String raw) {
        return EnumParsing.parse(Awaiting.class, raw, NOTHING, NOTHING);
    }
}
