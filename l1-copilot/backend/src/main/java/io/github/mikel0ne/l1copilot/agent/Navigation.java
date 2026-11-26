package io.github.mikel0ne.l1copilot.agent;

public enum Navigation {

    NONE,

    BACK_TO_LIST,

    BACK_TO_MENU,

    BACK;

    public static Navigation fromLlm(String raw) {
        return EnumParsing.parse(Navigation.class, raw, NONE, NONE);
    }
}
