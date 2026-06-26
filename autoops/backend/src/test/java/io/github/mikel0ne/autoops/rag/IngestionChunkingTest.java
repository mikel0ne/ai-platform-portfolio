package io.github.mikel0ne.autoops.rag;

import io.github.mikel0ne.autoops.rag.source.Documento;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Troceado de markdown: secciones con breadcrumb, orden creciente, tamaño objetivo y ventana de vecinos. */
class IngestionChunkingTest {

    private static Documento doc(String fuente, String md) {
        return new Documento(fuente, fuente, fuente, "markdown-local", "", md);
    }

    @Test
    void trocea_por_secciones_con_breadcrumb_y_orden() {
        String md = """
                # Título
                Intro.

                ## Sección A
                Párrafo A1.

                ## Sección B
                Párrafo B1.
                """;

        List<Chunk> chunks = Chunker.trocear(doc("guia.md", md), 2000, 200);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.fuente()).isEqualTo("guia.md");
            assertThat(c.origen()).isEqualTo("markdown-local");
            assertThat(c.hash()).isNotBlank();
        });
        // El breadcrumb concatena el título del doc con el encabezado de la sección.
        assertThat(chunks).extracting(Chunk::seccion).contains("Título > Sección A", "Título > Sección B");
        // El orden es estrictamente creciente desde 0.
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).orden()).isEqualTo(i);
        }
    }

    @Test
    void el_nucleo_respeta_el_tamano_objetivo_aunque_la_seccion_sea_larga() {
        String parrafo = "palabra ".repeat(60); // ~480 chars
        String md = "## Larga\n" + (parrafo + "\n\n").repeat(6); // ~2900 chars en la sección

        List<Chunk> chunks = Chunker.trocear(doc("larga.md", md), 1000, 100);

        assertThat(chunks).hasSizeGreaterThan(1);
        // El núcleo (lo que se embebe) respeta el objetivo; la ventana almacenada puede ser mayor.
        assertThat(chunks).allSatisfy(c -> assertThat(c.nucleo().length()).isLessThanOrEqualTo(1000));
    }

    @Test
    void la_ventana_almacenada_incluye_el_chunk_anterior_y_el_posterior() {
        // Tres párrafos que no caben juntos → tres núcleos; el del medio debe ver a sus dos vecinos.
        String md = """
                ## S
                AAAAA primero.

                BBBBB segundo.

                CCCCC tercero.
                """;

        List<Chunk> chunks = Chunker.trocear(doc("v.md", md), 20, 5);

        assertThat(chunks).hasSize(3);
        // El núcleo del chunk del medio es solo su propio párrafo...
        assertThat(chunks.get(1).nucleo()).contains("BBBBB").doesNotContain("AAAAA", "CCCCC");
        // ...pero el texto almacenado (la ventana) trae el anterior y el posterior.
        assertThat(chunks.get(1).texto()).contains("AAAAA", "BBBBB", "CCCCC");
        // Los extremos solo tienen un vecino.
        assertThat(chunks.get(0).texto()).contains("AAAAA", "BBBBB").doesNotContain("CCCCC");
        assertThat(chunks.get(2).texto()).contains("BBBBB", "CCCCC").doesNotContain("AAAAA");
    }
}
