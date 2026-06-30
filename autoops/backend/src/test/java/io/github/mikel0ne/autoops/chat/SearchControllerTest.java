package io.github.mikel0ne.autoops.chat;

import io.github.mikel0ne.autoops.rag.DocChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Agrupado de citas para la respuesta al usuario (cuestión de fuentes duplicadas, doc 05 §2). */
class SearchControllerTest {

    private static DocChunk chunk(String fuente, String seccion, String texto, double score) {
        return new DocChunk(texto, "Título", fuente, seccion, "", "test", score, "doc", 0, 0);
    }

    @Test
    void colapsa_dos_nucleos_de_la_misma_seccion_en_una_sola_cita_la_de_mayor_score() {
        List<DocChunk> citas = SearchController.agruparPorSeccion(List.of(
                chunk("03-tools-mcp.md", "Núcleo RAG", "fragmento A", 4.1),
                chunk("03-tools-mcp.md", "Núcleo RAG", "fragmento B", 0.5)));

        assertThat(citas).hasSize(1);
        assertThat(citas.get(0).score()).isEqualTo(4.1);
        assertThat(citas.get(0).texto()).isEqualTo("fragmento A");
    }

    @Test
    void no_colapsa_secciones_distintas_del_mismo_fichero() {
        List<DocChunk> citas = SearchController.agruparPorSeccion(List.of(
                chunk("guia.md", "Sección A", "a", 0.9),
                chunk("guia.md", "Sección B", "b", 0.8)));

        assertThat(citas).hasSize(2);
    }

    @Test
    void preserva_el_orden_de_aparicion() {
        List<DocChunk> citas = SearchController.agruparPorSeccion(List.of(
                chunk("b.md", "S", "b", 0.5),
                chunk("a.md", "S", "a", 0.9)));

        assertThat(citas).extracting(DocChunk::fuente).containsExactly("b.md", "a.md");
    }
}
