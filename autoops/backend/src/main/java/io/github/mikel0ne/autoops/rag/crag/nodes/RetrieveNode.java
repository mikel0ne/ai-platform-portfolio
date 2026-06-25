package io.github.mikel0ne.autoops.rag.crag.nodes;

import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;

/**
 * Nodo {@code retrieve}: búsqueda híbrida (vector + BM25) con reranking sobre la consulta vigente
 * (doc 03 §3.2). Deja los candidatos en el estado.
 */
public class RetrieveNode implements NodeAction<CragState> {

    private final DocRetriever retriever;

    public RetrieveNode(DocRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public Map<String, Object> apply(CragState state) {
        List<DocChunk> candidatos = retriever.retrieve(state.consultaActual(), state.k());
        return Map.of("candidatos", candidatos);
    }
}
