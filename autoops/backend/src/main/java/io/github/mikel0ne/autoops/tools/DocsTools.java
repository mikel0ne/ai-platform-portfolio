package io.github.mikel0ne.autoops.tools;

import io.github.mikel0ne.autoops.rag.RagResult;
import io.github.mikel0ne.autoops.rag.RagService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Tool MCP de documentación: {@code buscar_docs} (doc 03 §1.1, doc 05 §1). Devuelve fragmentos relevantes
 * con su fuente y score tras búsqueda híbrida + reranking + bucle correctivo (CRAG). <b>No redacta</b>: el
 * cliente MCP razona sobre los chunks (a diferencia del chat {@code /search}, que sí redacta).
 */
@Component
public class DocsTools {

    private final RagService ragService;

    public DocsTools(RagService ragService) {
        this.ragService = ragService;
    }

    @Tool(name = "buscar_docs",
          description = "Busca en la documentación interna y devuelve los fragmentos más relevantes con su "
                  + "fuente, sección y score. Úsala para responder dudas sobre estándares, políticas y guías "
                  + "internas. Devuelve también las iteraciones del bucle correctivo y la confianza.")
    public RagResult buscarDocs(
            @ToolParam(description = "Consulta en lenguaje natural") String consulta,
            @ToolParam(required = false, description = "Nº de fragmentos a devolver (por defecto 5)") Integer k) {
        return ragService.buscar(consulta, k == null ? 0 : k);
    }
}
