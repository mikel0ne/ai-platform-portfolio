package io.github.mikel0ne.autoops.rag.crag.nodes;

import dev.langchain4j.service.output.OutputParsingException;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import io.github.mikel0ne.autoops.rag.crag.llm.QueryVariantGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class MultiQueryNode implements NodeAction<CragState> {

    private final DocRetriever retriever;
    private final QueryVariantGenerator generator;
    private final int n;

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
        List<String> generated;
        try {
            generated = generator.generate(query, count).variants();
        } catch (OutputParsingException e) {
            log.warn("Unparseable query variants, searching with the original query only: {}", e.getMessage());
            return List.of();
        }
        if (generated == null) {
            return List.of();
        }
        return generated.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isBlank() && !v.equalsIgnoreCase(query))
                .distinct()
                .limit(count)
                .toList();
    }
}
