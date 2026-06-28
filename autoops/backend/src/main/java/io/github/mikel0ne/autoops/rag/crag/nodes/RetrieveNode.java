package io.github.mikel0ne.autoops.rag.crag.nodes;

import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;

/**
 * Nodo {@code retrieve}: búsqueda híbrida (vector + BM25) sobre la consulta vigente, en el camino de
 * reintento del bucle correctivo ({@code rewrite → retrieve}); la primera vuelta la hace {@code multiQuery}
 * (doc 03 §3.2, §3.2bis). <b>Acumula</b> los candidatos con los de iteraciones previas (en vez de
 * sobreescribirlos): un chunk bueno encontrado en una vuelta no se pierde si la siguiente reformulación no
 * vuelve a traerlo en el nuevo top-k. {@code rerank} se encarga de reordenar y recortar el conjunto acumulado
 * a {@code k}.
 */
public class RetrieveNode implements NodeAction<CragState> {

    private final DocRetriever retriever;

    public RetrieveNode(DocRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public Map<String, Object> apply(CragState state) {
        List<DocChunk> nuevos = retriever.retrieve(state.consultaActual(), state.k());
        List<DocChunk> acumulados = DocChunkDedup.merge(state.candidatos(), nuevos);
        return Map.of("candidatos", acumulados);
    }
}
