package io.github.mikel0ne.l1copilot.agent.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface RawVerifier {

    @SystemMessage(fromResource = "/prompts/raw_verifier.txt")
    @UserMessage("""
            GESTIÓN QUE SE VA A REALIZAR: {{intent}}
            PEDIDO EN FOCO: {{focus}}
            MENSAJE DEL USUARIO: {{message}}
            """)
    String verify(@V("message") String message,
                  @V("intent") String intent,
                  @V("focus") String focus);
}
