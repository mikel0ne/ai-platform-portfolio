package io.github.mikel0ne.autoops.rag.crag.nodes;

import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;

// Reranking already happened inside the Weaviate query; this keeps the best k after fusion.
public class TopKNode implements NodeAction<CragState> {

    @Override
    public Map<String, Object> apply(CragState state) {
        List<DocChunk> trimmed = state.candidates().stream().limit(state.k()).toList();
        return Map.of("candidates", trimmed, "rawCandidates", trimmed);
    }
}
