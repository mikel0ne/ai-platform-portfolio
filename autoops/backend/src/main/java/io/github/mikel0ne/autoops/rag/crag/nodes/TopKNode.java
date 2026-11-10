package io.github.mikel0ne.autoops.rag.crag.nodes;

import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;

public class RerankNode implements NodeAction<CragState> {

    @Override
    public Map<String, Object> apply(CragState state) {
        List<DocChunk> trimmed = state.candidates().stream().limit(state.k()).toList();
        return Map.of("candidates", trimmed, "rawCandidates", trimmed);
    }
}
