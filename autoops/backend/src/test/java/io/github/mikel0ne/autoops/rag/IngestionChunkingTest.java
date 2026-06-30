package io.github.mikel0ne.autoops.rag;

import io.github.mikel0ne.autoops.rag.source.Documento;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Troceado de markdown: secciones con breadcrumb, orden creciente, tamaño objetivo y seccionChars. */
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
        // El núcleo (lo que se embebe Y se almacena) respeta el objetivo.
        assertThat(chunks).allSatisfy(c -> assertThat(c.texto().length()).isLessThanOrEqualTo(1000));
    }

    @Test
    void el_texto_almacenado_es_el_nucleo_sin_ventana_de_vecinos() {
        // Tres párrafos que no caben juntos → tres núcleos; cada chunk almacena SOLO su propio núcleo
        // (ya no se hornea la ventana con el anterior/posterior; ese contexto lo da el CRAG en consulta).
        String md = """
                ## S
                AAAAA primero.

                BBBBB segundo.

                CCCCC tercero.
                """;

        List<Chunk> chunks = Chunker.trocear(doc("v.md", md), 20, 5);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).texto()).contains("AAAAA").doesNotContain("BBBBB", "CCCCC");
        assertThat(chunks.get(1).texto()).contains("BBBBB").doesNotContain("AAAAA", "CCCCC");
        assertThat(chunks.get(2).texto()).contains("CCCCC").doesNotContain("AAAAA", "BBBBB");
    }

    @Test
    void seccionChars_es_la_suma_de_los_nucleos_de_la_seccion() {
        // Dos secciones; cada chunk lleva el tamaño total (caracteres) de SU sección, igual para todos
        // los núcleos de la misma sección. Sirve al CRAG para decidir la expansión sin consultar Weaviate.
        String md = """
                ## A
                AAAAA primero.

                BBBBB segundo.

                ## B
                CCCCC unico.
                """;

        List<Chunk> chunks = Chunker.trocear(doc("s.md", md), 20, 5);

        var seccionA = chunks.stream().filter(c -> c.seccion().endsWith("A")).toList();
        var seccionB = chunks.stream().filter(c -> c.seccion().endsWith("B")).toList();
        assertThat(seccionA).hasSize(2);
        assertThat(seccionB).hasSize(1);
        // Todos los chunks de A comparten el mismo seccionChars = suma de sus núcleos.
        int sumaA = seccionA.stream().mapToInt(c -> c.texto().length()).sum();
        assertThat(seccionA).allSatisfy(c -> assertThat(c.seccionChars()).isEqualTo(sumaA));
        // B tiene un solo núcleo → seccionChars = su propia longitud.
        assertThat(seccionB.get(0).seccionChars()).isEqualTo(seccionB.get(0).texto().length());
    }
}
