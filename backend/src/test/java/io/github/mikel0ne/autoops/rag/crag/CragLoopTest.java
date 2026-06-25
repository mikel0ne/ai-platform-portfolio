package io.github.mikel0ne.autoops.rag.crag;

import io.github.mikel0ne.autoops.config.PromptLoader;
import io.github.mikel0ne.autoops.rag.RagResult;
import io.github.mikel0ne.autoops.rag.RagService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El bucle correctivo (CRAG) sobre dobles: converge cuando la recuperación mejora, reformula cuando es
 * floja y siempre termina (doc 03 §3, §5). Sin LLM real, sin Weaviate.
 */
class CragLoopTest {

    private final PromptLoader prompts = new PromptLoader();

    private RagService service(TestSupport.FakeRetriever retriever, CragProperties props) {
        var chat = new TestSupport.ScriptedChatModel("estandar de probes readiness liveness");
        var graph = new CragGraph(retriever, TestSupport.provider(chat), props, prompts);
        return new RagService(graph);
    }

    @Test
    void converge_a_la_primera_sin_reformular() {
        var retriever = new TestSupport.FakeRetriever(0.9); // ya buena en el primer intento
        var props = new CragProperties(5, 0.7, 2, 0.5);

        RagResult result = service(retriever, props).buscar("probes", 5);

        assertThat(result.iteraciones()).isZero();
        assertThat(result.confianza()).isEqualTo(RagResult.ALTA);
        assertThat(result.chunks()).isNotEmpty();
        assertThat(retriever.calls).isEqualTo(1);
    }

    @Test
    void reformula_una_vez_y_mejora() {
        var retriever = new TestSupport.FakeRetriever(0.2, 0.9); // floja, luego buena tras reformular
        var props = new CragProperties(5, 0.7, 2, 0.5);

        RagResult result = service(retriever, props).buscar("prbs", 5);

        assertThat(result.iteraciones()).isEqualTo(1);
        assertThat(result.confianza()).isEqualTo(RagResult.ALTA);
        assertThat(retriever.calls).isEqualTo(2); // retrieve inicial + retrieve tras rewrite
    }

    @Test
    void siempre_termina_y_devuelve_lo_mejor_aunque_no_supere_el_umbral() {
        var retriever = new TestSupport.FakeRetriever(0.2); // nunca supera el umbral
        var props = new CragProperties(5, 0.7, 2, 0.5);

        RagResult result = service(retriever, props).buscar("consulta rara", 5);

        assertThat(result.iteraciones()).isEqualTo(2); // tope de reformulaciones
        assertThat(result.confianza()).isEqualTo(RagResult.BAJA);
        assertThat(result.chunks()).isNotEmpty(); // nunca deja al cliente sin nada
    }
}
