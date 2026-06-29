package io.github.mikel0ne.autoops.rag;

import io.github.mikel0ne.autoops.rag.source.GitHubMarkdownSource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Disparadores de ingesta del corpus (doc 03 §F1: "comando/endpoint de ingesta"). Operaciones de
 * mantenimiento: indexan markdown en Weaviate o vacían la base vectorial.
 */
@RestController
public class IngestionController {

    private final IngestionService ingestion;

    public IngestionController(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    /**
     * Indexa el corpus local (y Confluence si está configurado). Con {@code reset=true} borra primero la
     * clase y reindexa desde cero (necesario, p. ej., al cambiar de modelo de embeddings y su dimensión —
     * doc 06 §4).
     */
    @PostMapping("/admin/ingest")
    public Map<String, Object> ingest(@RequestParam(defaultValue = "false") boolean reset) {
        int chunks = reset ? ingestion.reindex() : ingestion.ingestAll();
        return Map.of("chunks", chunks, "reset", reset);
    }

    /**
     * Indexa un repo Git puntual, pasado en la propia llamada (no en config): así se puede ir alimentando
     * la wiki con varios repos, uno por llamada. Se acumula sobre lo ya indexado; para partir de cero usar
     * antes {@code POST /admin/vectorstore/drop}. Si no se indica {@code branch}, se detecta la rama por
     * defecto del repo (la que reporta GitHub, no necesariamente {@code main}).
     */
    @PostMapping("/admin/ingest/git")
    public Map<String, Object> ingestGit(@RequestParam String url,
                                          @RequestParam(required = false) String branch,
                                          @RequestParam(required = false) String subdir,
                                          @RequestParam(required = false) String webBase) {
        var repo = new GitHubMarkdownSource.GitRepo(url, branch, subdir, webBase);
        var resultado = ingestion.ingestGitRepo(repo);
        return Map.of("url", resultado.url(), "branch", resultado.branch(), "chunks", resultado.chunks());
    }

    /** Vacía la base vectorial sin reingestar nada, para empezar de cero antes de encadenar varias ingestas. */
    @PostMapping("/admin/vectorstore/drop")
    public Map<String, Object> dropVectorStore() {
        ingestion.dropVectorStore();
        return Map.of("dropped", true);
    }
}
