package io.github.mikel0ne.autoops.rag.crag.nodes;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.autoops.config.PromptTemplates;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import lombok.RequiredArgsConstructor;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

@RequiredArgsConstructor
public class RewriteNode implements NodeAction<CragState> {

    private final ChatModel chatModel;
    private final String promptTemplate;
    private final int kStep;
    private final int kMax;

    @Override
    public Map<String, Object> apply(CragState state) {
        String prompt = PromptTemplates.render(promptTemplate, Map.of(
                "original_query", state.query(),
                "current_query", state.currentQuery()));
        String rewritten = chatModel.chat(prompt).trim();
        if (rewritten.isBlank()) {
            rewritten = state.currentQuery();
        }
        int widenedK = Math.min(state.k() + kStep, kMax);
        return Map.of(
                "currentQuery", rewritten,
                "k", widenedK,
                "iterations", state.iterations() + 1);
    }
}
