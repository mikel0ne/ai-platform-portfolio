package io.github.mikel0ne.autoops.chat;

import io.github.mikel0ne.autoops.rag.RagResult;
import io.github.mikel0ne.autoops.rag.RagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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
        String respuesta = answerComposer.compose(request.consulta(), rag);
        return new SearchResponse(respuesta, rag.chunks(), rag.confianza());
    }
}
