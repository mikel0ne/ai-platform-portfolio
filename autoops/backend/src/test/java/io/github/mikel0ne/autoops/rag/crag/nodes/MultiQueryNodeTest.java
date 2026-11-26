package io.github.mikel0ne.autoops.rag.crag.nodes;

import dev.langchain4j.service.output.OutputParsingException;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import io.github.mikel0ne.autoops.rag.crag.llm.QueryVariantGenerator.QueryVariants;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MultiQueryNodeTest {

    private static final DocChunk CHUNK = new DocChunk("text", "Title", "doc.md", "Section", "", "test", 0.5, "", 0, 0);

    private static CragState state(String query) {
        Map<String, Object> data = new HashMap<>();
        data.put("query", query);
        data.put("k", 5);
        return new CragState(data);
    }

    private static DocRetriever retriever() {
        return new DocRetriever() {
            @Override
            public List<DocChunk> retrieve(String query, int k) {
                return List.of(CHUNK);
            }

            @Override
            public List<DocChunk> fullSection(String docId, String section) {
                return List.of();
            }

            @Override
            public List<DocChunk> neighboursInSection(String docId, String section, int order, int radius) {
                return List.of();
            }
        };
    }

    @Test
    void searches_the_original_query_and_its_variants() {
        var node = new MultiQueryNode(retriever(), (q, n) -> new QueryVariants(List.of("variant")), 2);

        Map<String, Object> out = node.apply(state("original"));

        assertThat(out.get("questions")).isEqualTo(List.of("original", "variant"));
    }

    @Test
    void unparseable_variants_degrade_to_the_original_query() {
        var node = new MultiQueryNode(retriever(), (q, n) -> {
            throw new OutputParsingException("not json", new IllegalArgumentException("unexpected token"));
        }, 3);

        Map<String, Object> out = node.apply(state("original"));

        assertThat(out.get("questions")).isEqualTo(List.of("original"));
        assertThat(out.get("candidates")).isEqualTo(List.of(CHUNK));
    }
}
