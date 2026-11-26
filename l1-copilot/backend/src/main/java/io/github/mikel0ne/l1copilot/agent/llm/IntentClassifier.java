package io.github.mikel0ne.l1copilot.agent.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface IntentClassifier {

    @SystemMessage(fromResource = "/prompts/intent_classifier.txt")
    @UserMessage("""
            <historial_no_fiable>
            {{history}}
            </historial_no_fiable>

            MENSAJE ACTUAL DEL USUARIO:
            {{message}}
            """)
    RouterDecision classify(@V("message") String message,
                            @V("currentIntent") String currentIntent,
                            @V("awaiting") String awaiting,
                            @V("candidates") String candidates,
                            @V("focusOrder") String focusOrder,
                            @V("history") String history);

    /** Strips any copy of the untrusted-history delimiter so user text cannot close the fence early. */
    public static String fenceSafe(String history) {
        return history.replaceAll("(?i)</?\\s*historial_no_fiable[^>]*>", "");
    }

    record RouterDecision(String intent, Long orderId, String confirmation, String navigation,
                          boolean orderRelated) {
        public RouterDecision(String intent, Long orderId, String confirmation, String navigation) {
            this(intent, orderId, confirmation, navigation, false);
        }
    }
}
