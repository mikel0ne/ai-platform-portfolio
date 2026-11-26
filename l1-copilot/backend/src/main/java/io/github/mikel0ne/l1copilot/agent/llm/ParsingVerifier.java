package io.github.mikel0ne.l1copilot.agent.llm;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ParsingVerifier implements Verifier {

    private final RawVerifier raw;

    @Override
    public Decision verify(String message, String intent, String focus) {
        return DecisionParser.parse(raw.verify(message, intent, focus));
    }
}
