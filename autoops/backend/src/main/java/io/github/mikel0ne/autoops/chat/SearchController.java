package io.github.mikel0ne.autoops.chat;

import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.RagResult;
import io.github.mikel0ne.autoops.rag.RagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Front secundario de búsqueda (doc 05 §2): {@code POST /search} sobre el mismo núcleo RAG que la tool MCP,
 * más un paso de redacción con el LLM interno. Sin autenticación en el MVP.
 */
@RestController
public class SearchController {

    private final RagService ragService;
    private final AnswerComposer answerComposer;

    public SearchController(RagService ragService, AnswerComposer answerComposer) {
        this.ragService = ragService;
        this.answerComposer = answerComposer;
    }

    @PostMapping("/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        RagResult rag = ragService.buscar(request.consulta(), 0);
        // El redactor recibe TODOS los fragmentos (contexto completo, grounding); solo las citas que se
        // muestran al usuario se agrupan por sección, para no enseñar tarjetas duplicadas de la misma fuente.
        // En confianza nula, AnswerComposer recurre a candidatosDescartados; las citas mostradas usan la
        // misma base para no dejar al usuario sin saber de dónde sale lo que se le acaba de contar.
        String respuesta = answerComposer.compose(request.consulta(), rag);
        List<DocChunk> base = rag.chunks().isEmpty() ? rag.candidatosDescartados() : rag.chunks();
        return new SearchResponse(respuesta, agruparPorSeccion(base), rag.confianza());
    }

    /**
     * Agrupa las citas por {@code (fuente, seccion)} quedándose con la de mayor score y preservando el orden
     * de aparición. Evita mostrar dos tarjetas de la misma sección (p. ej. dos núcleos que pasaron grade). El
     * auto-merge del CRAG ya colapsa el caso común; esto es un backstop idempotente (doc 05 §2).
     */
    static List<DocChunk> agruparPorSeccion(List<DocChunk> chunks) {
        Map<String, DocChunk> porClave = new LinkedHashMap<>();
        for (DocChunk c : chunks) {
            porClave.merge(c.fuente() + " | " + c.seccion(), c, (a, b) -> b.score() > a.score() ? b : a);
        }
        return List.copyOf(porClave.values());
    }
}
