package io.github.mikel0ne.l1copilot.agent.llm;

public class ParsingVerifier implements Verifier {

    private final RawVerifier raw;

    public ParsingVerifier(RawVerifier raw) {
        this.raw = raw;
    }

    @Override
    public Decision verify(String message, String intent, String focus) {
        return DecisionParser.parse(raw.verify(message, intent, focus));
    }
}
