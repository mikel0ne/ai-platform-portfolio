package io.github.mikel0ne.autoops.rag;

import io.github.mikel0ne.platform.commons.langfuse.LangfuseAttributes;
import io.github.mikel0ne.autoops.observability.langfuse.LangfuseScoreGateway;
import io.github.mikel0ne.autoops.observability.langfuse.LangfuseTracing;
import io.github.mikel0ne.autoops.rag.crag.Coverage;
import io.github.mikel0ne.autoops.rag.crag.CragGraph;
import io.github.mikel0ne.autoops.rag.crag.CragProperties;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class RagService {

    private static final int MAX_DISCARDED = 3;

    private final CragGraph cragGraph;
    private final CragProperties props;
    private final LangfuseTracing tracing;
    private final LangfuseScoreGateway scores;

    public RagResult search(String query, int k) {
        int effectiveK = k > 0 ? Math.min(k, props.kMax()) : props.k();
        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        input.put("currentQuery", query);
        input.put("k", effectiveK);

        CragState state = invokeGraph(input, query);

        List<DocChunk> chunks = state.candidates();
        int iterations = state.iterations();
        List<Coverage> coverage = state.questionCoverage();

        Confidence confidence = confidenceOf(coverage);
        List<DocChunk> discarded = confidence != Confidence.NONE ? List.of() : state.rawCandidates().stream()
                .sorted(Comparator.comparingDouble(DocChunk::score).reversed())
                .limit(MAX_DISCARDED)
                .toList();

        log.debug("search_docs: query='{}' k={} iterations={} bestScore={} coverage={} confidence={}",
                query, effectiveK, iterations, state.bestScore(), coverage, confidence);

        publishTelemetry(effectiveK, chunks.size(), iterations, confidence, coverage);
        return new RagResult(chunks, iterations, confidence, discarded);
    }

    // The node spans come from the global hook of CragGraph; this one groups them under "crag".
    private CragState invokeGraph(Map<String, Object> input, String query) {
        Span span = tracing.child("crag", LangfuseAttributes.TYPE_CHAIN);
        tracing.input(span, query);
        try (Scope ignored = span.makeCurrent()) {
            return cragGraph.get()
                    .invoke(input, RunnableConfig.empty())
                    .orElseThrow(() -> new IllegalStateException("The CRAG graph returned no final state"));
        } catch (RuntimeException e) {
            tracing.fail(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    // The "crag" span is already closed, so the current span is the trace root: trace metadata is
    // trace-level and scores reference the trace id, not an observation.
    private void publishTelemetry(int effectiveK, int chunks, int iterations, Confidence confidence,
                                  List<Coverage> coverage) {
        Span trace = Span.current();
        tracing.traceMetadata(trace, "confidence", confidence.code());
        tracing.traceMetadata(trace, "iterations", iterations);
        tracing.traceMetadata(trace, "k", effectiveK);
        tracing.traceMetadata(trace, "chunks", chunks);
        tracing.traceMetadata(trace, "coverage", coverage);

        scores.send(tracing.currentTraceId(), List.of(
                LangfuseScoreGateway.categorical("rag_confidence", confidence.code()),
                LangfuseScoreGateway.numeric("crag_iterations", iterations)));
    }

    static Confidence confidenceOf(List<Coverage> coverage) {
        if (!coverage.isEmpty() && coverage.stream().allMatch(Coverage.COVERED::equals)) {
            return Confidence.HIGH;
        }
        if (coverage.stream().anyMatch(Coverage.COVERED::equals)) {
            return Confidence.MEDIUM;
        }
        if (coverage.stream().anyMatch(Coverage.PARTIAL::equals)) {
            return Confidence.LOW;
        }
        return Confidence.NONE;
    }
}
