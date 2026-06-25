package io.github.mikel0ne.autoops.rag.crag.nodes;

import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

/**
 * Nodo {@code grade}: calificación <b>determinista</b> por el mejor score (no usa LLM, que podría alucinar
 * la calidad — doc 03 §3.2). Deja {@code mejorScore} en el estado; la decisión de seguir o parar la toma
 * el edge condicional del grafo.
 */
public class GradeNode implements NodeAction<CragState> {

    @Override
    public Map<String, Object> apply(CragState state) {
        double mejor = state.candidatos().stream()
                .mapToDouble(DocChunk::score)
                .max()
                .orElse(0.0);
        return Map.of("mejorScore", mejor);
    }
}
