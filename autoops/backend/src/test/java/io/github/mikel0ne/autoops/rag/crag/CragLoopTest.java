package io.github.mikel0ne.autoops.rag.crag;

import io.github.mikel0ne.autoops.config.PromptLoader;
import io.github.mikel0ne.autoops.rag.Confidence;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.RagResult;
import io.github.mikel0ne.autoops.rag.RagService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.mikel0ne.autoops.rag.crag.TestSupport.grades;
import static io.github.mikel0ne.autoops.rag.crag.TestSupport.variants;
import static org.assertj.core.api.Assertions.assertThat;

class CragLoopTest {

    private final PromptLoader prompts = new PromptLoader();

    private RagService service(DocRetriever retriever, CragProperties props,
                                List<String> gradeResponses, List<String> rewriteResponses) {
        var chat = new TestSupport.ScriptedChatModel(gradeResponses, rewriteResponses);
        var graph = new CragGraph(retriever, TestSupport.provider(chat), props, prompts);
        return new RagService(graph, props);
    }

    @Test
    void converges_on_first_pass_without_rewriting() {
        var retriever = new TestSupport.FakeRetriever(0.9);
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        RagResult result = service(retriever, props, List.of(grades("SUFFICIENT")), List.of())
                .search("probes", 5);

        assertThat(result.iterations()).isZero();
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(result.chunks()).isNotEmpty();
        assertThat(retriever.calls).isEqualTo(1);
        assertThat(retriever.receivedKs).containsExactly(5);
    }

    @Test
    void caps_the_requested_k_at_k_max() {
        var retriever = new TestSupport.FakeRetriever(0.9);
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        service(retriever, props, List.of(grades("SUFFICIENT")), List.of()).search("probes", 500);

        assertThat(retriever.receivedKs).containsExactly(12);
    }

    @Test
    void rewrites_once_and_improves() {
        var retriever = new TestSupport.FakeRetriever(0.2, 0.9);
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        RagResult result = service(retriever, props,
                List.of(grades("INSUFFICIENT"), grades("SUFFICIENT", "INSUFFICIENT")), List.of("prbs reformulada"))
                .search("prbs", 5);

        assertThat(result.iterations()).isEqualTo(1);
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(retriever.calls).isEqualTo(2);
        assertThat(retriever.receivedKs).containsExactly(5, 8);
    }

    @Test
    void always_terminates_and_keeps_nothing_when_all_is_insufficient() {
        var retriever = new TestSupport.FakeRetriever(0.2);
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        RagResult result = service(retriever, props,
                List.of(grades("INSUFFICIENT"), grades("INSUFFICIENT", "INSUFFICIENT"),
                        grades("INSUFFICIENT", "INSUFFICIENT", "INSUFFICIENT")),
                List.of("reformulada 1", "reformulada 2"))
                .search("consulta rara", 5);

        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.confidence()).isEqualTo(Confidence.NONE);
        assertThat(result.chunks()).isEmpty();
        assertThat(result.discardedCandidates()).isNotEmpty();
        assertThat(retriever.calls).isEqualTo(3);
        assertThat(retriever.receivedKs).containsExactly(5, 8, 11);
    }

    @Test
    void partial_coverage_of_a_question_fan_out_gives_medium_confidence() {
        var n1 = new DocChunk("Responde a la original.", "Guía", "probes.md", "A", "", "test", 0.9, "", 0, 0);
        var n2 = new DocChunk("No responde a nada en concreto.", "Guía", "probes.md", "B", "", "test",
                0.5, "", 0, 0);
        var retriever = new TestSupport.MultiChunkRetriever(List.of(List.of(n1, n2)));
        var props = new CragProperties(5, 0, 0.5, 3, 12, 2);
        var chat = new TestSupport.ScriptedChatModel(
                List.of(grades(List.of("SUFFICIENT", "INSUFFICIENT"), List.of("COVERED", "NOT_COVERED"))),
                List.of(), List.of(variants("variante")));
        var graph = new CragGraph(retriever, TestSupport.provider(chat), props, prompts);

        RagResult result = new RagService(graph, props).search("pregunta compuesta", 0);

        assertThat(result.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(result.chunks()).containsExactly(n1);
    }

    @Test
    void only_ambiguous_on_all_questions_gives_low_confidence() {
        var n1 = new DocChunk("Toca el tema pero no lo resuelve.", "Guía", "probes.md", "A", "", "test",
                0.9, "", 0, 0);
        var retriever = new TestSupport.MultiChunkRetriever(List.of(List.of(n1)));
        var props = new CragProperties(5, 0, 0.5, 3, 12, 1);

        RagResult result = service(retriever, props,
                List.of(grades(List.of("AMBIGUOUS"), List.of("PARTIAL"))), List.of())
                .search("pregunta dudosa", 5);

        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
        assertThat(result.chunks()).containsExactly(n1);
    }

    @Test
    void high_but_irrelevant_score_does_not_skip_llm_grading() {
        var retriever = new TestSupport.FakeRetriever(5.0, 5.0);
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        RagResult result = service(retriever, props,
                List.of(grades("INSUFFICIENT"), grades("INSUFFICIENT", "SUFFICIENT")), List.of("reformulada"))
                .search("recursos minimos de un microservicio", 5);

        assertThat(result.iterations()).isEqualTo(1);
        assertThat(retriever.calls).isEqualTo(2);
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void multi_query_fan_out_only_on_first_pass_is_fused_with_rrf() {
        var retriever = new TestSupport.FakeRetriever(0.9, 0.5, 0.5);
        var props = new CragProperties(2, 2, 0.5, 3, 12, 3);
        var chat = new TestSupport.ScriptedChatModel(
                List.of(grades("SUFFICIENT", "SUFFICIENT")), List.of(),
                List.of(variants("variante uno", "variante dos")));
        var graph = new CragGraph(retriever, TestSupport.provider(chat), props, prompts);

        RagResult result = new RagService(graph, props).search("consulta original", 0);

        assertThat(retriever.calls).isEqualTo(3);
        assertThat(retriever.receivedQueries)
                .containsExactly("consulta original", "variante uno", "variante dos");
        assertThat(retriever.receivedKs).containsExactly(2, 2, 2);

        assertThat(result.chunks()).hasSize(2);
        assertThat(result.iterations()).isZero();
    }

    @Test
    void multi_query_n_one_behaves_like_a_single_retrieve() {
        var retriever = new TestSupport.FakeRetriever(0.9);
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        var chat = new TestSupport.ScriptedChatModel(List.of(grades("SUFFICIENT")), List.of());
        var graph = new CragGraph(retriever, TestSupport.provider(chat), props, prompts);

        RagResult result = new RagService(graph, props).search("probes", 0);

        assertThat(retriever.calls).isEqualTo(1);
        assertThat(retriever.receivedQueries).containsExactly("probes");
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void filters_out_irrelevant_chunks_from_the_batch() {
        var good1 = new DocChunk("Las probes de liveness se configuran con...", "Guía", "probes.md",
                "Liveness", "", "test", 0.9, "", 0, 0);
        var bad = new DocChunk("La política de vacaciones del equipo es...", "RRHH", "rrhh.md",
                "Vacaciones", "", "test", 0.8, "", 0, 0);
        var good2 = new DocChunk("El timeout recomendado para probes es 5s", "Guía", "probes.md",
                "Timeouts", "", "test", 0.7, "", 0, 0);
        var retriever = new TestSupport.MultiChunkRetriever(List.of(List.of(good1, bad, good2)));
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        RagResult result = service(retriever, props,
                List.of(grades("SUFFICIENT", "INSUFFICIENT", "SUFFICIENT")), List.of())
                .search("configuracion de probes", 5);

        assertThat(result.chunks()).containsExactly(good1, good2);
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(result.iterations()).isZero();
    }

    @Test
    void retry_keeps_a_good_chunk_from_the_first_iteration() {
        var good = new DocChunk("Las probes de liveness se configuran con...", "Guía", "probes.md",
                "Liveness", "", "test", 0.9, "", 0, 0);
        var bad1 = new DocChunk("La política de vacaciones del equipo es...", "RRHH", "rrhh.md",
                "Vacaciones", "", "test", 0.1, "", 0, 0);
        var fresh1 = new DocChunk("Otro fragmento sin relación con la consulta", "Otros", "otros.md",
                "Seccion X", "", "test", 0.5, "", 0, 0);
        var fresh2 = new DocChunk("Otro fragmento más sin relación", "Otros", "otros.md",
                "Seccion Y", "", "test", 0.4, "", 0, 0);
        var retriever = new TestSupport.MultiChunkRetriever(List.of(
                List.of(good, bad1),
                List.of(fresh1, fresh2)));
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        RagResult result = service(retriever, props,
                List.of(
                        grades("INSUFFICIENT", "INSUFFICIENT"),
                        grades("SUFFICIENT", "INSUFFICIENT", "INSUFFICIENT", "INSUFFICIENT")),
                List.of("probes reformulada"))
                .search("configuracion de probes", 5);

        assertThat(result.iterations()).isEqualTo(1);
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(result.chunks()).containsExactly(good);
    }

    @Test
    void case_b_merges_a_single_hit_in_a_small_section() {
        var shortChunk = new DocChunk("Ver tabla de timeouts.", "Guía", "probes.md", "Timeouts", "", "test",
                0.9, "doc-probes", 200, 0);
        var part1 = new DocChunk("Las probes de liveness y readiness necesitan...",
                "Guía", "probes.md", "Timeouts", "", "test", 0.0, "doc-probes", 200, 0);
        var part2 = new DocChunk("...tabla de timeouts recomendados por tipo de probe.",
                "Guía", "probes.md", "Timeouts", "", "test", 0.0, "doc-probes", 200, 1);
        var retriever = new TestSupport.MultiChunkRetriever(
                List.of(List.of(shortChunk)),
                Map.of("doc-probes", List.of(part1, part2)));
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        RagResult result = service(retriever, props, List.of(grades("SUFFICIENT")), List.of())
                .search("timeouts de las probes", 5);

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).text())
                .contains("Las probes de liveness", "tabla de timeouts recomendados");
        assertThat(result.chunks().get(0).score()).isEqualTo(0.9);
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void case_a_merges_the_section_when_several_of_its_cores_are_relevant() {
        var n1 = new DocChunk("Núcleo uno de la sección.", "Guía", "probes.md", "Timeouts", "", "test",
                0.9, "doc-x", 5000, 0);
        var n2 = new DocChunk("Núcleo dos de la sección.", "Guía", "probes.md", "Timeouts", "", "test",
                0.6, "doc-x", 5000, 1);
        var f1 = new DocChunk("Núcleo uno de la sección.", "Guía", "probes.md", "Timeouts", "", "test",
                0.0, "doc-x", 5000, 0);
        var f2 = new DocChunk("Núcleo dos de la sección.", "Guía", "probes.md", "Timeouts", "", "test",
                0.0, "doc-x", 5000, 1);
        var f3 = new DocChunk("Núcleo tres, no recuperado.", "Guía", "probes.md", "Timeouts", "", "test",
                0.0, "doc-x", 5000, 2);
        var retriever = new TestSupport.MultiChunkRetriever(
                List.of(List.of(n1, n2)),
                Map.of("doc-x", List.of(f1, f2, f3)));
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        RagResult result = service(retriever, props, List.of(grades("SUFFICIENT", "SUFFICIENT")), List.of())
                .search("timeouts", 5);

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).text())
                .contains("Núcleo uno", "Núcleo dos", "Núcleo tres");
        assertThat(retriever.sectionCalls).isEqualTo(1);
    }

    @Test
    void case_c_returns_section_bounded_neighbours_without_merging() {
        var before = new DocChunk("Núcleo anterior.", "Guía", "probes.md", "Timeouts", "", "test",
                0.0, "doc-z", 5000, 3);
        var hit = new DocChunk("Núcleo del medio.", "Guía", "probes.md", "Timeouts", "", "test",
                0.9, "doc-z", 5000, 4);
        var after = new DocChunk("Núcleo posterior.", "Guía", "probes.md", "Timeouts", "", "test",
                0.0, "doc-z", 5000, 5);
        var farChunk = new DocChunk("Núcleo lejano.", "Guía", "probes.md", "Timeouts", "", "test",
                0.0, "doc-z", 5000, 9);
        var retriever = new TestSupport.MultiChunkRetriever(
                List.of(List.of(hit)),
                Map.of("doc-z", List.of(before, hit, after, farChunk)));
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        RagResult result = service(retriever, props, List.of(grades("SUFFICIENT")), List.of())
                .search("timeouts", 5);

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).text())
                .contains("Núcleo anterior", "Núcleo del medio", "Núcleo posterior")
                .doesNotContain("Núcleo lejano");
        assertThat(retriever.sectionCalls).isZero();
        assertThat(retriever.neighbourCalls).isEqualTo(1);
    }

    @Test
    void ceiling_skips_merge_for_an_oversized_section() {
        var n1 = new DocChunk("Uno.", "Guía", "probes.md", "Timeouts", "", "test", 0.9, "doc-w", 12000, 0);
        var n2 = new DocChunk("Dos.", "Guía", "probes.md", "Timeouts", "", "test", 0.6, "doc-w", 12000, 1);
        var retriever = new TestSupport.MultiChunkRetriever(List.of(List.of(n1, n2)), Map.of());
        var props = new CragProperties(5, 2, 0.5, 3, 12, 1);

        RagResult result = service(retriever, props, List.of(grades("SUFFICIENT", "SUFFICIENT")), List.of())
                .search("timeouts", 5);

        assertThat(result.chunks()).containsExactly(n1, n2);
        assertThat(retriever.sectionCalls).isZero();
        assertThat(retriever.neighbourCalls).isZero();
    }
}
