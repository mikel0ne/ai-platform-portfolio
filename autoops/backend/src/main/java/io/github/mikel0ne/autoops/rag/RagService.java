package io.github.mikel0ne.autoops.rag;

import io.github.mikel0ne.autoops.rag.crag.CragGraph;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import io.github.mikel0ne.autoops.rag.crag.nodes.GradeNode;
import org.bsc.langgraph4j.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
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

    /** Tope de candidatos descartados que se ofrecen como último recurso en confianza {@code nula}. */
    private static final int TOPE_DESCARTADOS = 3;

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
        List<String> cobertura = estado.coberturaPreguntas();

        String confianza;
        List<DocChunk> descartados = List.of();
        if (!cobertura.isEmpty() && cobertura.stream().allMatch(GradeNode.CUBIERTA::equals)) {
            confianza = RagResult.ALTA;
        } else if (cobertura.stream().anyMatch(GradeNode.CUBIERTA::equals)) {
            confianza = RagResult.MEDIA;
        } else if (cobertura.stream().anyMatch(GradeNode.PARCIAL::equals)) {
            confianza = RagResult.BAJA;
        } else {
            confianza = RagResult.NULA;
            descartados = estado.candidatosCrudos().stream()
                    .sorted(Comparator.comparingDouble(DocChunk::score).reversed())
                    .limit(TOPE_DESCARTADOS)
                    .toList();
        }

        log.debug("buscar_docs: consulta='{}' k={} iteraciones={} mejorScore={} cobertura={} confianza={}",
                consulta, kEfectivo, iteraciones, estado.mejorScore(), cobertura, confianza);
        return new RagResult(chunks, iteraciones, confianza, descartados);
    }
}
