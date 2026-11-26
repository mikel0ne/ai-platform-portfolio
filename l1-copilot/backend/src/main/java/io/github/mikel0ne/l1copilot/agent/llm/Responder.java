package io.github.mikel0ne.l1copilot.agent.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface Responder {

    @SystemMessage(fromResource = "/prompts/responder.txt")
    @UserMessage("""
            TRATAMIENTO: {{treatment}}
            INTENT: {{intent}}
            AWAITING: {{awaiting}}
            CARDS_SHOWN: {{cardsShown}}
            TENTATIVO: {{tentative}}
            PEDIDO_EN_FOCO: {{orderContext}}
            HISTORIAL_RECIENTE:
            {{history}}
            TOOL_RESULT: {{toolResult}}
            MENSAJE DEL USUARIO: {{message}}
            """)
    String write(@V("intent") String intent,
                 @V("awaiting") String awaiting,
                 @V("toolResult") String toolResult,
                 @V("message") String message,
                 @V("cardsShown") boolean cardsShown,
                 @V("orderContext") String orderContext,
                 @V("treatment") String treatment,
                 @V("tentative") boolean tentative,
                 @V("history") String history);
}
