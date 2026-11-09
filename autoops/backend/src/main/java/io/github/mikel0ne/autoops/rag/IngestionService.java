package io.github.mikel0ne.autoops.rag;

import io.github.mikel0ne.autoops.rag.source.SourceDocument;
import io.github.mikel0ne.autoops.rag.source.DocumentSource;
import io.github.mikel0ne.autoops.rag.source.GitHubMarkdownSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final WeaviateGateway gateway;
    private final IngestionProperties props;
    private final List<DocumentSource> sources;
    private final GitHubMarkdownSource gitHubSource;

    public IngestionService(WeaviateGateway gateway, IngestionProperties props, List<DocumentSource> sources,
                             GitHubMarkdownSource gitHubSource) {
        this.gateway = gateway;
        this.props = props;
        this.sources = sources;
        this.gitHubSource = gitHubSource;
    }

    public int reindex() {
        gateway.dropSchema();
        gateway.ensureSchema();
        return ingestAll();
    }

    public int ingestAll() {
        gateway.ensureSchema();
        List<Chunk> chunks = splitAll();
        if (chunks.isEmpty()) {
            log.warn("No source returned documents to index");
            return 0;
        }
        int n = gateway.ingest(chunks);
        log.info("Ingestion finished: {} chunks from {} sources", n, sources.size());
        return n;
    }

    private List<Chunk> splitAll() {
        List<Chunk> chunks = new ArrayList<>();
        for (DocumentSource source : sources) {
            List<SourceDocument> docs = source.read();
            for (SourceDocument doc : docs) {
                chunks.addAll(Chunker.splitDocument(doc, props.maxChars(), props.overlapChars()));
            }
            log.info("Source '{}': {} documents split", source.name(), docs.size());
        }
        return chunks;
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
        int n = gateway.ingest(chunks);
        log.info("Git ingestion finished: {} chunks from {}@{}", n, repo.url(), repo.branch());
        return new IngestResult(repo.url(), repo.branch(), n);
    }

    public void dropVectorStore() {
        gateway.dropSchema();
        gateway.ensureSchema();
    }

    public record IngestResult(String url, String branch, int chunks) {}
}
