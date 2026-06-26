package io.github.mikel0ne.autoops.rag.crag;

import io.github.mikel0ne.autoops.config.PromptLoader;
import io.github.mikel0ne.autoops.rag.RagResult;
import io.github.mikel0ne.autoops.rag.RagService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El bucle correctivo (CRAG) sobre dobles: converge cuando el LLM evaluador da el visto bueno, reformula y
 * amplía {@code k} cuando no, y siempre termina (doc 03 §3, §5). Sin LLM real, sin Weaviate.
 */
class CragLoopTest {

    private final PromptLoader prompts = new PromptLoader();

    private RagService service(TestSupport.FakeRetriever retriever, CragProperties props,
                                List<String> gradeRespuestas, List<String> rewriteRespuestas) {
        var chat = new TestSupport.ScriptedChatModel(gradeRespuestas, rewriteRespuestas);
        var graph = new CragGraph(retriever, TestSupport.provider(chat), props, prompts);
        return new RagService(graph);
    }

    @Test
    void converge_a_la_primera_sin_reformular() {
        var retriever = new TestSupport.FakeRetriever(0.9);
        var props = new CragProperties(5, 2, 0.5, 3, 12);

        RagResult result = service(retriever, props, List.of("SUFICIENTE"), List.of())
                .buscar("probes", 5);

        assertThat(result.iteraciones()).isZero();
        assertThat(result.confianza()).isEqualTo(RagResult.ALTA);
        assertThat(result.chunks()).isNotEmpty();
        assertThat(retriever.calls).isEqualTo(1);
        assertThat(retriever.kRecibidos).containsExactly(5);
    }

    @Test
    void reformula_una_vez_y_mejora() {
        var retriever = new TestSupport.FakeRetriever(0.2, 0.9);
        var props = new CragProperties(5, 2, 0.5, 3, 12);

        RagResult result = service(retriever, props,
                List.of("INSUFICIENTE", "SUFICIENTE"), List.of("prbs reformulada"))
                .buscar("prbs", 5);

        assertThat(result.iteraciones()).isEqualTo(1);
        assertThat(result.confianza()).isEqualTo(RagResult.ALTA);
        assertThat(retriever.calls).isEqualTo(2); // retrieve inicial + retrieve tras rewrite
        assertThat(retriever.kRecibidos).containsExactly(5, 8); // k crece kStep=3 en el reintento
    }

    @Test
    void siempre_termina_y_devuelve_lo_mejor_aunque_nunca_sea_suficiente() {
        var retriever = new TestSupport.FakeRetriever(0.2);
        var props = new CragProperties(5, 2, 0.5, 3, 12);

        RagResult result = service(retriever, props,
                List.of("INSUFICIENTE", "INSUFICIENTE", "INSUFICIENTE"),
                List.of("reformulada 1", "reformulada 2"))
                .buscar("consulta rara", 5);

        assertThat(result.iteraciones()).isEqualTo(2); // tope de reformulaciones
        assertThat(result.confianza()).isEqualTo(RagResult.BAJA);
        assertThat(result.chunks()).isNotEmpty(); // nunca deja al cliente sin nada
        assertThat(retriever.calls).isEqualTo(3); // inicial + 2 reintentos
        assertThat(retriever.kRecibidos).containsExactly(5, 8, 11); // k sigue creciendo hasta el tope
    }

    @Test
    void score_alto_pero_irrelevante_no_evita_la_evaluacion_del_llm() {
        // Regresión del bug visto en producción: un score crudo alto (reranker/híbrido) no implica que el
        // fragmento responda a la pregunta. 'grade' no debe aceptar por el score solo: si el LLM dice
        // INSUFICIENTE, el grafo reformula y reintenta aunque el score sea muy alto en ambas vueltas.
        var retriever = new TestSupport.FakeRetriever(5.0, 5.0);
        var props = new CragProperties(5, 2, 0.5, 3, 12);

        RagResult result = service(retriever, props,
                List.of("INSUFICIENTE", "SUFICIENTE"), List.of("reformulada"))
                .buscar("recursos minimos de un microservicio", 5);

        assertThat(result.iteraciones()).isEqualTo(1);
        assertThat(retriever.calls).isEqualTo(2); // si hubiera atajo por score, sería 1
        assertThat(result.confianza()).isEqualTo(RagResult.ALTA);
    }
}
