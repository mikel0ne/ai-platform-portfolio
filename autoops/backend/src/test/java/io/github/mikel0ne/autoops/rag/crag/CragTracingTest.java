package io.github.mikel0ne.autoops.rag.crag;

import io.github.mikel0ne.autoops.config.PromptLoader;
import io.github.mikel0ne.autoops.observability.langfuse.LangfuseTestTracing;
import io.github.mikel0ne.autoops.observability.langfuse.LangfuseTracing;
import io.github.mikel0ne.autoops.rag.RagService;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.mikel0ne.autoops.rag.crag.TestSupport.grades;
import static org.assertj.core.api.Assertions.assertThat;

// Span hierarchy of a full RAG query: the real CRAG graph over doubles (no LLM, Weaviate or network) and an
// in-memory exporter. Covers what can really break: one span per node, generations hanging from the node
// that caused them, and the attribute names Langfuse maps.
class CragTracingTest {

    private final PromptLoader prompts = new PromptLoader();

    @Test
    void a_query_produces_the_root_crag_node_generation_hierarchy() {
        LangfuseTestTracing t = LangfuseTestTracing.recording();
        RagService service = service(t, List.of(grades("SUFFICIENT")), List.of());

        runInRoot(t, service, "test query");

        Map<String, String> parents = t.parentNames();
        assertThat(parents).containsEntry("search_docs", "<root>");
        assertThat(parents).containsEntry("crag", "search_docs");
        assertThat(parents).containsEntry("multiQuery", "crag");
        assertThat(parents).containsEntry("topK", "crag");
        assertThat(parents).containsEntry("grade", "crag");
        assertThat(parents.get("gemini.chat")).isIn("multiQuery", "grade");
        assertThat(t.spanNames()).contains("gemini.chat");
    }

    @Test
    void every_node_carries_the_observation_type_langfuse_maps() {
        LangfuseTestTracing t = LangfuseTestTracing.recording();
        RagService service = service(t, List.of(grades("SUFFICIENT")), List.of());

        runInRoot(t, service, "test query");

        assertThat(type(t.span("crag"))).isEqualTo("chain");
        assertThat(type(t.span("multiQuery"))).isEqualTo("chain");
        assertThat(type(t.span("grade"))).isEqualTo("chain");
        assertThat(type(t.span("topK"))).isEqualTo("span");
        assertThat(type(t.span("expand"))).isEqualTo("span");
        assertThat(type(t.span("gemini.chat"))).isEqualTo("generation");
    }

    @Test
    void the_root_carries_the_trace_metadata_of_the_rag_result() {
        LangfuseTestTracing t = LangfuseTestTracing.recording();
        RagService service = service(t, List.of(grades("SUFFICIENT")), List.of());

        runInRoot(t, service, "test query");

        SpanData root = t.span("search_docs");
        assertThat(attr(root, "langfuse.trace.name")).isEqualTo("search_docs");
        assertThat(attr(root, "langfuse.environment")).isEqualTo("test");
        assertThat(attr(root, "langfuse.release")).isEqualTo("0.0.0-test");
        assertThat(attr(root, "langfuse.trace.metadata.confidence")).isEqualTo("high");
        assertThat(attr(root, "langfuse.trace.metadata.k")).isEqualTo("3");
        assertThat(attr(root, "langfuse.trace.metadata.iterations")).isEqualTo("0");
        assertThat(attr(root, "langfuse.trace.metadata.chunks")).isNotNull();
        assertThat(root.getAttributes().get(AttributeKey.stringArrayKey("langfuse.trace.tags")))
                .containsExactly("autoops", "rag");
    }

    @Test
    void the_corrective_loop_emits_one_span_per_lap_of_each_node() {
        LangfuseTestTracing t = LangfuseTestTracing.recording();
        RagService service = service(t,
                List.of(grades("INSUFFICIENT"), grades("SUFFICIENT")), List.of("reworded query"));

        runInRoot(t, service, "weak query");

        assertThat(t.spanNames()).contains("rewrite", "retrieve");
        assertThat(t.spanNames().stream().filter("grade"::equals)).hasSize(2);
        assertThat(t.parentNames()).containsEntry("rewrite", "crag");
    }

    private void runInRoot(LangfuseTestTracing t, RagService service, String query) {
        Span root = t.tracing().root("search_docs", "tool", query, List.of("autoops", "rag"));
        try (Scope ignored = root.makeCurrent()) {
            service.search(query, 3);
        } finally {
            root.end();
        }
    }

    private RagService service(LangfuseTestTracing t, List<String> gradeResponses, List<String> rewriteResponses) {
        var chat = new TestSupport.ScriptedChatModel(gradeResponses, rewriteResponses);
        var instrumented = new TestSupport.ListeningChatModel(
                chat, t.chatListener("internal"));
        LangfuseTracing tracing = t.tracing();
        var props = new CragProperties(5, 2, 0.5, 3, 12, 2);
        var graph = new CragGraph(new TestSupport.FakeRetriever(0.9), TestSupport.provider(instrumented), props,
                prompts, tracing);
        return new RagService(graph, props, tracing, LangfuseTestTracing.noopScores());
    }

    private static String type(SpanData span) {
        return attr(span, "langfuse.observation.type");
    }

    private static String attr(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }
}
