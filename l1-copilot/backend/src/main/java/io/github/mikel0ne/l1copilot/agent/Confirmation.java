package io.github.mikel0ne.l1copilot.agent;

public enum Confirmation {

    YES, NO, NONE;

    public static Confirmation fromState(String raw) {
        return EnumParsing.parse(Confirmation.class, raw, NONE, NONE);
    }
}
