package io.github.mikel0ne.autoops.rag.crag.nodes;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

public class RewriteNode implements NodeAction<CragState> {

    private final ChatModel chatModel;
    private final String promptTemplate;
    private final int kStep;
    private final int kMax;

    public RewriteNode(ChatModel chatModel, String promptTemplate, int kStep, int kMax) {
        this.chatModel = chatModel;
        this.promptTemplate = promptTemplate;
        this.kStep = kStep;
        this.kMax = kMax;
    }

    @Override
    public Map<String, Object> apply(CragState state) {
        String prompt = promptTemplate
                .replace("{{original_query}}", state.query())
                .replace("{{current_query}}", state.currentQuery());
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
