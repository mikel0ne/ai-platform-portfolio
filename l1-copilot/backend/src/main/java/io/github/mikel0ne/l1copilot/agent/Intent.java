package io.github.mikel0ne.l1copilot.agent;

public enum Intent {

    TRACKING, CANCEL, RETURN, SUPPORT, TICKETS, FAQ, CHATTY, OTHER, NONE;

    public static Intent fromLlm(String raw) {
        return EnumParsing.parse(Intent.class, raw, NONE, OTHER);
    }
}
