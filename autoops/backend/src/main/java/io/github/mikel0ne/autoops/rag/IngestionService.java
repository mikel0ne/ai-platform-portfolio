package io.github.mikel0ne.autoops.rag;

import io.github.mikel0ne.autoops.rag.source.SourceDocument;
import io.github.mikel0ne.autoops.rag.source.DocumentSource;
import io.github.mikel0ne.autoops.rag.source.GitHubMarkdownSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class IngestionService {

    private final WeaviateGateway gateway;
    private final IngestionProperties props;
    private final List<DocumentSource> sources;
    private final GitHubMarkdownSource gitHubSource;

    public int reindex() {
        gateway.dropSchema();
        gateway.ensureSchema();
        return ingestAll();
    }

    public int ingestAll() {
        gateway.ensureSchema();
        Map<String, List<Chunk>> bySource = splitAll();
        int total = 0;
        for (Map.Entry<String, List<Chunk>> entry : bySource.entrySet()) {
            total += gateway.ingest(entry.getValue(), entry.getKey(), null);
        }
        if (total == 0) {
            log.warn("No source returned documents to index");
        }
        log.info("Ingestion finished: {} chunks from {} sources", total, sources.size());
        return total;
    }

    // Every source is read before anything is written, and an empty one is skipped rather than
    // taken as "all its documents vanished".
    private Map<String, List<Chunk>> splitAll() {
        Map<String, List<Chunk>> bySource = new LinkedHashMap<>();
        for (DocumentSource source : sources) {
            List<SourceDocument> docs = source.read();
            log.info("Source '{}': {} documents split", source.name(), docs.size());
            if (docs.isEmpty()) {
                continue;
            }
            List<Chunk> chunks = new ArrayList<>();
            for (SourceDocument doc : docs) {
                chunks.addAll(Chunker.splitDocument(doc, props.maxChars(), props.overlapChars()));
            }
            bySource.put(source.name(), chunks);
        }
        return bySource;
    }

    public IngestResult ingestGitRepo(GitHubMarkdownSource.GitRepo repoInput) {
        GitHubMarkdownSource.GitRepo repo = gitHubSource.resolveRepo(repoInput);
        List<SourceDocument> docs = gitHubSource.read(repo);
        List<Chunk> chunks = new ArrayList<>();
        for (SourceDocument doc : docs) {
            chunks.addAll(Chunker.splitDocument(doc, props.maxChars(), props.overlapChars()));
        }
        if (chunks.isEmpty()) {
            log.warn("Repo {}@{} returned no documents to index", repo.url(), repo.branch());
            return new IngestResult(repo.url(), repo.branch(), 0);
        }
        gateway.ensureSchema();
        int n = gateway.ingest(chunks, "git", GitHubMarkdownSource.docIdPrefix(repo));
        log.info("Git ingestion finished: {} chunks from {}@{}", n, repo.url(), repo.branch());
        return new IngestResult(repo.url(), repo.branch(), n);
    }

    public void dropVectorStore() {
        gateway.dropSchema();
        gateway.ensureSchema();
    }

    public record IngestResult(String url, String branch, int chunks) {}
}
