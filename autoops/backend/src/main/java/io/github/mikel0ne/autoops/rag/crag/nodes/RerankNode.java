package io.github.mikel0ne.autoops.rag.crag.nodes;

import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Nodo {@code rerank}: deja el conjunto ordenado por relevancia descendente y recortado a {@code k}.
 * El reranking pesado ya lo hace Weaviate en el {@code retrieve}; este nodo fija un orden determinista
 * y el tamaño del conjunto (doc 03 §3.2).
 */
public class RerankNode implements NodeAction<CragState> {

    @Override
    public Map<String, Object> apply(CragState state) {
        List<DocChunk> ordenados = state.candidatos().stream()
                .sorted(Comparator.comparingDouble(DocChunk::score).reversed())
                .limit(state.k())
                .toList();
        return Map.of("candidatos", ordenados);
    }
}
