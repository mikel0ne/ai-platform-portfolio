package io.github.mikel0ne.l1copilot.agent.node;

import io.github.mikel0ne.l1copilot.agent.Awaiting;
import io.github.mikel0ne.l1copilot.agent.SupportState;
import io.github.mikel0ne.l1copilot.agent.llm.FaqKnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class FaqNode implements NodeAction<SupportState> {

    private final FaqKnowledgeBase knowledgeBase;

    @Override
    public Map<String, Object> apply(SupportState state) {
        boolean digression = state.awaiting() != Awaiting.NOTHING;
        log.debug("---FAQ--- digression={}", digression);

        return Map.of(
                SupportState.TOOL_RESULT, "Base de conocimiento de la tienda (usa solo lo "
                        + "relevante para la pregunta del usuario):\n" + knowledgeBase.content(),
                SupportState.RESOLVED, !digression);
    }
}
