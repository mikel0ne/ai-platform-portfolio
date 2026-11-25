package io.github.mikel0ne.l1copilot.agent.llm;

public interface Verifier {

    Decision verify(String message, String intent, String focus);

    record Decision(String verdict, String reason) {
    }
}
