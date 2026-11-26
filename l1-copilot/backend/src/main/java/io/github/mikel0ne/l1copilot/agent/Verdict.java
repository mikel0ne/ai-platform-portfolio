package io.github.mikel0ne.l1copilot.agent;

public enum Verdict {

    // NONE: nothing was verified in this turn, so there is no verdict to report.
    SURE, DOUBT, REJECT, NONE;

    public static Verdict fromLlm(String raw) {
        return EnumParsing.parse(Verdict.class, raw, DOUBT, DOUBT);
    }
}
