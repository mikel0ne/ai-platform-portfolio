package io.github.mikel0ne.autoops.rag;

import io.github.mikel0ne.autoops.rag.source.SourceDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionChunkingTest {

    private static SourceDocument doc(String source, String md) {
        return new SourceDocument(source, source, source, "markdown-local", "", md);
    }

    @Test
    void splits_by_section_with_breadcrumb_and_order() {
        String md = """
                # Título
                Intro.

                ## Sección A
                Párrafo A1.

                ## Sección B
                Párrafo B1.
                """;

        List<Chunk> chunks = Chunker.splitDocument(doc("guia.md", md), 2000, 200);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.source()).isEqualTo("guia.md");
            assertThat(c.origin()).isEqualTo("markdown-local");
            assertThat(c.hash()).isNotBlank();
        });

        assertThat(chunks).extracting(Chunk::section).contains("Título > Sección A", "Título > Sección B");

        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).order()).isEqualTo(i);
        }
    }

    @Test
    void core_respects_target_size_even_in_a_long_section() {
        String paragraphText = "palabra ".repeat(60);
        String md = "## Larga\n" + (paragraphText + "\n\n").repeat(6);

        List<Chunk> chunks = Chunker.splitDocument(doc("larga.md", md), 1000, 100);

        assertThat(chunks).hasSizeGreaterThan(1);

        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(1000));
    }

    @Test
    void stored_text_is_the_core_without_neighbour_window() {
        String md = """
                ## S
                AAAAA primero.

                BBBBB segundo.

                CCCCC tercero.
                """;

        List<Chunk> chunks = Chunker.splitDocument(doc("v.md", md), 20, 5);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).text()).contains("AAAAA").doesNotContain("BBBBB", "CCCCC");
        assertThat(chunks.get(1).text()).contains("BBBBB").doesNotContain("AAAAA", "CCCCC");
        assertThat(chunks.get(2).text()).contains("CCCCC").doesNotContain("AAAAA", "BBBBB");
    }

    @Test
    void sectionChars_is_the_sum_of_the_section_cores() {
        String md = """
                ## A
                AAAAA primero.

                BBBBB segundo.

                ## B
                CCCCC unico.
                """;

        List<Chunk> chunks = Chunker.splitDocument(doc("s.md", md), 20, 5);

        var sectionA = chunks.stream().filter(c -> c.section().endsWith("A")).toList();
        var sectionB = chunks.stream().filter(c -> c.section().endsWith("B")).toList();
        assertThat(sectionA).hasSize(2);
        assertThat(sectionB).hasSize(1);

        int sumA = sectionA.stream().mapToInt(c -> c.text().length()).sum();
        assertThat(sectionA).allSatisfy(c -> assertThat(c.sectionChars()).isEqualTo(sumA));

        assertThat(sectionB.get(0).sectionChars()).isEqualTo(sectionB.get(0).text().length());
    }
}
