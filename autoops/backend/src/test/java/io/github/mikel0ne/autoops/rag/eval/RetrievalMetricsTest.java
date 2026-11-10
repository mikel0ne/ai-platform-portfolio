package io.github.mikel0ne.autoops.rag.eval;

import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.eval.RetrievalMetrics.GoldenCase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RetrievalMetricsTest {

    private static DocChunk chunk(String source, String section) {
        return new DocChunk("text", "Title", source, section, "", "git", 1.0, "doc", 0, 0);
    }

    private final GoldenCase golden = new GoldenCase("q", "guia-probes.md", List.of("Readiness"));

    @Test
    void ranks_by_the_first_chunk_of_the_expected_document_and_section() {
        List<DocChunk> retrieved = List.of(
                chunk("autoops/corpus/politica-recursos.md", "Política > CPU"),
                chunk("autoops/corpus/guia-probes.md", "Guía > Liveness probe"),
                chunk("autoops/corpus/guia-probes.md", "Guía > Readiness probe"));

        assertThat(RetrievalMetrics.rank(golden, retrieved)).isEqualTo(3);
        assertThat(RetrievalMetrics.rank(golden, retrieved.subList(0, 2))).isZero();
    }

    @Test
    void hit_rate_and_mrr_over_several_cases() {
        List<Integer> ranks = List.of(1, 2, 0, 4);

        assertThat(RetrievalMetrics.hitRate(ranks, 1)).isEqualTo(0.25);
        assertThat(RetrievalMetrics.hitRate(ranks, 3)).isEqualTo(0.5);
        assertThat(RetrievalMetrics.meanReciprocalRank(ranks)).isCloseTo((1 + 0.5 + 0 + 0.25) / 4, within(1e-9));
    }
}
