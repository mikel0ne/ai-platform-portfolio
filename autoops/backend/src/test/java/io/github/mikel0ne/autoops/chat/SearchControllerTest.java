package io.github.mikel0ne.autoops.chat;

import io.github.mikel0ne.autoops.rag.DocChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchControllerTest {

    private static DocChunk chunk(String source, String section, String text, double score) {
        return new DocChunk(text, "Título", source, section, "", "test", score, "doc", 0, 0);
    }

    @Test
    void collapses_two_cores_of_a_section_into_the_best_scoring_citation() {
        List<DocChunk> citations = SearchController.groupBySection(List.of(
                chunk("03-tools-mcp.md", "Núcleo RAG", "fragmento A", 4.1),
                chunk("03-tools-mcp.md", "Núcleo RAG", "fragmento B", 0.5)));

        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).score()).isEqualTo(4.1);
        assertThat(citations.get(0).text()).isEqualTo("fragmento A");
    }

    @Test
    void does_not_collapse_different_sections_of_the_same_file() {
        List<DocChunk> citations = SearchController.groupBySection(List.of(
                chunk("guia.md", "Sección A", "a", 0.9),
                chunk("guia.md", "Sección B", "b", 0.8)));

        assertThat(citations).hasSize(2);
    }

    @Test
    void preserves_order_of_appearance() {
        List<DocChunk> citations = SearchController.groupBySection(List.of(
                chunk("b.md", "S", "b", 0.5),
                chunk("a.md", "S", "a", 0.9)));

        assertThat(citations).extracting(DocChunk::source).containsExactly("b.md", "a.md");
    }
}
