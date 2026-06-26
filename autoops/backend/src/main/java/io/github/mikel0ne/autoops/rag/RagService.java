package io.github.mikel0ne.autoops.rag;

import io.github.mikel0ne.autoops.rag.crag.CragGraph;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API interna del RAG, compartida por los dos fronts (tool MCP {@code buscar_docs} y chat {@code /search}).
 * Ejecuta el grafo correctivo (CRAG) y mapea el estado final al contrato {@link RagResult} (doc 03 §4).
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final CragGraph cragGraph;

    public RagService(CragGraph cragGraph) {
        this.cragGraph = cragGraph;
    }

    /**
     * Recupera fragmentos relevantes con búsqueda híbrida + reranking + bucle correctivo.
     *
     * @param consulta consulta del usuario
     * @param k        nº de fragmentos deseado (si {@code <= 0}, el valor por defecto del CRAG)
     */
    public RagResult buscar(String consulta, int k) {
        int kEfectivo = k > 0 ? k : cragGraph.props().k();
        Map<String, Object> input = new HashMap<>();
        input.put("consulta", consulta);
        input.put("consultaActual", consulta);
        input.put("k", kEfectivo);

        CragState estado = cragGraph.get()
                .invoke(input, RunnableConfig.empty())
                .orElseThrow(() -> new IllegalStateException("El grafo CRAG no devolvió estado final"));

        List<DocChunk> chunks = estado.candidatos();
        int iteraciones = estado.iteraciones();
        boolean suficiente = !chunks.isEmpty() && estado.suficiente();
        String confianza = suficiente ? RagResult.ALTA : RagResult.BAJA;

        log.debug("buscar_docs: consulta='{}' k={} iteraciones={} mejorScore={} suficiente={} confianza={}",
                consulta, kEfectivo, iteraciones, estado.mejorScore(), suficiente, confianza);
        return new RagResult(chunks, iteraciones, confianza);
    }
}
