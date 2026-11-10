package io.github.mikel0ne.autoops.rag;

import io.github.mikel0ne.autoops.rag.crag.CragGraph;
import io.github.mikel0ne.autoops.rag.crag.CragProperties;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import io.github.mikel0ne.autoops.rag.crag.nodes.GradeNode;
import org.bsc.langgraph4j.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private static final int MAX_DISCARDED = 3;

    private final CragGraph cragGraph;
    private final CragProperties props;

    public RagService(CragGraph cragGraph, CragProperties props) {
        this.cragGraph = cragGraph;
        this.props = props;
    }

    public RagResult search(String query, int k) {
        int effectiveK = k > 0 ? Math.min(k, props.kMax()) : props.k();
        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        input.put("currentQuery", query);
        input.put("k", effectiveK);

        CragState state = cragGraph.get()
                .invoke(input, RunnableConfig.empty())
                .orElseThrow(() -> new IllegalStateException("The CRAG graph returned no final state"));

        List<DocChunk> chunks = state.candidates();
        int iterations = state.iterations();
        List<String> coverage = state.questionCoverage();

        String confidence;
        List<DocChunk> discarded = List.of();
        if (!coverage.isEmpty() && coverage.stream().allMatch(GradeNode.COVERED::equals)) {
            confidence = RagResult.HIGH;
        } else if (coverage.stream().anyMatch(GradeNode.COVERED::equals)) {
            confidence = RagResult.MEDIUM;
        } else if (coverage.stream().anyMatch(GradeNode.PARTIAL::equals)) {
            confidence = RagResult.LOW;
        } else {
            confidence = RagResult.NONE;
            discarded = state.rawCandidates().stream()
                    .sorted(Comparator.comparingDouble(DocChunk::score).reversed())
                    .limit(MAX_DISCARDED)
                    .toList();
        }

        log.debug("search_docs: query='{}' k={} iterations={} bestScore={} coverage={} confidence={}",
                query, effectiveK, iterations, state.bestScore(), coverage, confidence);
        return new RagResult(chunks, iterations, confidence, discarded);
    }
}
