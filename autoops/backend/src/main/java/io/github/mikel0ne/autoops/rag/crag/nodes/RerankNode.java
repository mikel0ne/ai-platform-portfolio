package io.github.mikel0ne.autoops.rag.crag.nodes;

import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;

/**
 * Nodo {@code rerank}: recorta el conjunto a {@code k}, <b>preservando el orden con el que llega</b>. No
 * reordena por score crudo: quien lo precede ya lo entrega bien ordenado (Weaviate en una consulta única, o
 * RRF cuando hay varias consultas fusionadas en {@code multiQuery}/{@code retrieve}) — y el score crudo de
 * consultas distintas no es comparable entre sí, así que reordenar aquí por score deshacía esa fusión
 * (doc 03 §3.2). También deja en el estado {@code candidatosCrudos}: el pool de esta vuelta tal cual llega,
 * sin que {@code grade} lo filtre todavía — único recurso si al final nada se cataloga como bueno.
 */
public class RerankNode implements NodeAction<CragState> {

    @Override
    public Map<String, Object> apply(CragState state) {
        List<DocChunk> recortados = state.candidatos().stream().limit(state.k()).toList();
        return Map.of("candidatos", recortados, "candidatosCrudos", recortados);
    }
}
