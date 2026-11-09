package io.github.mikel0ne.autoops.rag.crag.nodes;

import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;

public class RetrieveNode implements NodeAction<CragState> {

    private final DocRetriever retriever;

    public RetrieveNode(DocRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public Map<String, Object> apply(CragState state) {
        List<DocChunk> fresh = retriever.retrieve(state.currentQuery(), state.k());
        List<DocChunk> merged = RrfFusion.merge(List.of(state.candidates(), fresh));
        return Map.of("candidates", merged);
    }
}
