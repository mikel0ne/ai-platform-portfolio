package io.github.mikel0ne.autoops.rag.crag.nodes;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MultiQueryNode implements NodeAction<CragState> {

    private final DocRetriever retriever;
    private final ChatModel chatModel;
    private final String promptTemplate;
    private final int n;

    public MultiQueryNode(DocRetriever retriever, ChatModel chatModel, String promptTemplate, int n) {
        this.retriever = retriever;
        this.chatModel = chatModel;
        this.promptTemplate = promptTemplate;
        this.n = n;
    }

    @Override
    public Map<String, Object> apply(CragState state) {
        String original = state.currentQuery();
        int k = state.k();
        // The fan-out runs on the first pass only; retries use a single rewritten query to bound LLM cost.
        if (n <= 1) {
            return Map.of("candidates", retriever.retrieve(original, k));
        }

        List<String> variants = new ArrayList<>();
        variants.add(original);
        variants.addAll(generateVariants(original, n - 1));

        List<List<DocChunk>> resultsByVariant = variants.stream()
                .map(v -> retriever.retrieve(v, k))
                .toList();

        List<DocChunk> merged = RrfFusion.merge(resultsByVariant).stream().limit(k).toList();
        return Map.of("candidates", merged, "questions", variants);
    }

    private List<String> generateVariants(String query, int count) {
        String prompt = promptTemplate
                .replace("{{query}}", query)
                .replace("{{n}}", String.valueOf(count));
        String answer = chatModel.chat(prompt);
        return answer.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank() && !line.equalsIgnoreCase(query))
                .distinct()
                .limit(count)
                .toList();
    }
}
