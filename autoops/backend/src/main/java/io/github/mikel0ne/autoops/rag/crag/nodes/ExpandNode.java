package io.github.mikel0ne.autoops.rag.crag.nodes;

import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Nodo {@code expand}: cuando {@code grade} ya dio el visto bueno, un fragmento relevante pero demasiado
 * corto (p. ej. el final de una sección con poco texto) puede no bastarle al redactor aunque haya sido
 * marcado relevante. Este nodo amplía esos fragmentos al documento completo (doc 03 §3.2).
 *
 * <p>Solo actúa en el camino "ok": si {@code grade} no dio el visto bueno (se agotaron las iteraciones sin
 * encontrar nada relevante), {@code candidatos} no son fragmentos confirmados como relevantes y no se tocan.
 */
public class ExpandNode implements NodeAction<CragState> {

    /** Por debajo de este nº de caracteres, un chunk relevante se considera "corto" y se expande al
     * documento completo. El núcleo objetivo de un chunk es de ~2000 caracteres (ver IngestionProperties);
     * un chunk muy por debajo de eso (incluida la ventana de vecinos) probablemente viene de una sección
     * breve y se queda corto de contexto para el redactor. */
    private static final int UMBRAL_CORTO = 400;

    private final DocRetriever retriever;

    public ExpandNode(DocRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public Map<String, Object> apply(CragState state) {
        if (!state.suficiente()) {
            return Map.of();
        }
        List<DocChunk> expandido = new ArrayList<>();
        for (DocChunk c : state.candidatos()) {
            expandido.addAll(expandirSiCorto(c));
        }
        return Map.of("candidatos", DocChunkDedup.merge(List.of(), expandido));
    }

    private List<DocChunk> expandirSiCorto(DocChunk c) {
        if (c.texto().length() >= UMBRAL_CORTO || c.docId() == null || c.docId().isBlank()) {
            return List.of(c);
        }
        List<DocChunk> documento = retriever.documentoCompleto(c.docId());
        return documento.isEmpty() ? List.of(c) : documento;
    }
}
