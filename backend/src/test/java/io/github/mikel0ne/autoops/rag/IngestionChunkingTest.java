package io.github.mikel0ne.autoops.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Troceado de markdown: por secciones, con orden creciente y respetando el tamaño objetivo. */
class IngestionChunkingTest {

    @Test
    void trocea_por_secciones_y_asigna_fuente_y_orden() {
        String md = """
                # Título
                Intro.

                ## Sección A
                Párrafo A1.

                ## Sección B
                Párrafo B1.
                """;

        List<WeaviateGateway.Doc> chunks = IngestionService.chunkDocument("guia.md", md, 2000, 200);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> assertThat(c.fuente()).isEqualTo("guia.md"));
        assertThat(chunks).extracting(WeaviateGateway.Doc::seccion).contains("Sección A", "Sección B");
        // el orden es estrictamente creciente desde 0
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).orden()).isEqualTo(i);
        }
    }

    @Test
    void parte_una_seccion_larga_en_varios_chunks_con_solape() {
        String parrafo = "palabra ".repeat(60); // ~480 chars
        String md = "## Larga\n" + (parrafo + "\n\n").repeat(6); // ~2900 chars en la sección

        List<WeaviateGateway.Doc> chunks = IngestionService.chunkDocument("larga.md", md, 1000, 100);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.seccion()).isEqualTo("Larga");
            assertThat(c.texto().length()).isLessThanOrEqualTo(1200); // tamaño objetivo + solape
        });
    }
}
