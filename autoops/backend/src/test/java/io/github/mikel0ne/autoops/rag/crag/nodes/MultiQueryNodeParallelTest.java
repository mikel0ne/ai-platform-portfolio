package io.github.mikel0ne.autoops.rag.crag.nodes;

import io.github.mikel0ne.autoops.observability.langfuse.LangfuseTestTracing;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import io.github.mikel0ne.autoops.rag.crag.llm.QueryVariantGenerator.QueryVariants;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiQueryNodeParallelTest {

    private static final Map<String, Long> SLEEP_MS = Map.of("q0", 100L, "q1", 100L, "q2", 20L, "q3", 60L);

    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @AfterEach
    void shutDown() {
        pool.shutdownNow();
    }

    private static CragState state() {
        Map<String, Object> data = new HashMap<>();
        data.put("query", "q0");
        data.put("k", 5);
        return new CragState(data);
    }

    private static DocChunk chunk(String query, double score) {
        return new DocChunk("text " + query, "Title", "doc-" + query + ".md", "S", "", "test", score, "", 0, 0);
    }

    private static DocRetriever retriever(RetrieveFn fn) {
        return new DocRetriever() {
            @Override
            public List<DocChunk> retrieve(String query, int k) {
                return fn.apply(query);
            }

            @Override
            public List<DocChunk> fullSection(String docId, String section) {
                return List.of();
            }

            @Override
            public List<DocChunk> neighboursInSection(String docId, String section, int order, int radius) {
                return List.of();
            }
        };
    }

    private static DocRetriever sleepingRetriever() {
        return retriever(q -> {
            try {
                Thread.sleep(SLEEP_MS.get(q));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return List.of(chunk(q, 0.5), chunk("shared", 0.4));
        });
    }

    private static final io.github.mikel0ne.autoops.rag.crag.llm.QueryVariantGenerator VARIANTS =
            (q, n) -> new QueryVariants(List.of("q1", "q2", "q3"));

    @Test
    void parallel_result_equals_the_sequential_one_and_is_faster() {
        var sequential = new MultiQueryNode(sleepingRetriever(), VARIANTS, 4);
        var parallel = new MultiQueryNode(sleepingRetriever(), VARIANTS, 4, pool);

        Map<String, Object> expected = sequential.apply(state());
        long start = System.nanoTime();
        Map<String, Object> actual = parallel.apply(state());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.get("questions")).isEqualTo(List.of("q0", "q1", "q2", "q3"));
        assertThat(elapsedMs).isLessThan(300);
    }

    @Test
    void results_keep_variant_order_even_when_later_variants_finish_first() {
        var onePerVariant = retriever(q -> {
            try {
                Thread.sleep(SLEEP_MS.get(q));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return List.of(chunk(q, 0.5));
        });
        var parallel = new MultiQueryNode(onePerVariant, VARIANTS, 4, pool);

        @SuppressWarnings("unchecked")
        List<DocChunk> candidates = (List<DocChunk>) parallel.apply(state()).get("candidates");

        assertThat(candidates).extracting(DocChunk::text).containsExactly("text q0", "text q1", "text q2", "text q3");
    }

    @Test
    void the_first_failure_by_variant_order_surfaces_with_its_own_exception() {
        var failing = retriever(q -> {
            if (q.equals("q1")) throw new IllegalStateException("boom q1");
            if (q.equals("q2")) throw new UnsupportedOperationException("boom q2");
            return List.of(chunk(q, 0.5));
        });
        var parallel = new MultiQueryNode(failing, VARIANTS, 4, pool);

        assertThatThrownBy(() -> parallel.apply(state()))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("boom q1");
    }

    @Test
    void retrieval_spans_keep_the_current_span_as_parent() {
        LangfuseTestTracing t = LangfuseTestTracing.recording();
        var traced = retriever(q -> {
            Span span = t.tracing().child("retrieve-" + q, "retriever");
            try (Scope ignored = span.makeCurrent()) {
                return List.of(chunk(q, 0.5));
            } finally {
                span.end();
            }
        });
        var parallel = new MultiQueryNode(traced, VARIANTS, 4, pool);

        Span parent = t.tracing().child("parent", "chain");
        try (Scope ignored = parent.makeCurrent()) {
            parallel.apply(state());
        } finally {
            parent.end();
        }

        Map<String, String> parents = t.parentNames();
        for (String q : List.of("q0", "q1", "q2", "q3")) {
            assertThat(parents).containsEntry("retrieve-" + q, "parent");
        }
    }

    private interface RetrieveFn {
        List<DocChunk> apply(String query);
    }
}
