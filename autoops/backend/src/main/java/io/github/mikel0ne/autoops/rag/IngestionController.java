package io.github.mikel0ne.autoops.rag;

import io.github.mikel0ne.autoops.rag.source.GitHubMarkdownSource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class IngestionController {

    private final IngestionService ingestion;

    public IngestionController(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping("/admin/ingest")
    public Map<String, Object> ingest(@RequestParam(defaultValue = "false") boolean reset) {
        int chunks = reset ? ingestion.reindex() : ingestion.ingestAll();
        return Map.of("chunks", chunks, "reset", reset);
    }

    @PostMapping("/admin/ingest/git")
    public Map<String, Object> ingestGit(@RequestParam String url,
                                          @RequestParam(required = false) String branch,
                                          @RequestParam(required = false) String subdir,
                                          @RequestParam(required = false) String webBase) {
        var repo = new GitHubMarkdownSource.GitRepo(url, branch, subdir, webBase);
        var result = ingestion.ingestGitRepo(repo);
        return Map.of("url", result.url(), "branch", result.branch(), "chunks", result.chunks());
    }

    @PostMapping("/admin/vectorstore/drop")
    public Map<String, Object> dropVectorStore() {
        ingestion.dropVectorStore();
        return Map.of("dropped", true);
    }
}
