package io.github.mikel0ne.autoops.rag;

import io.github.mikel0ne.autoops.rag.source.Documento;
import io.github.mikel0ne.autoops.rag.source.FuenteDocumental;
import io.github.mikel0ne.autoops.rag.source.GitHubMarkdownSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Indexa el corpus en Weaviate: recorre todas las {@link FuenteDocumental} disponibles (markdown local,
 * Wiki.js...), trocea cada documento en chunks con el {@link Chunker} (secciones + ventana de vecinos),
 * calcula sus embeddings y los inserta (doc 03 §F1, doc 05 §4). El troceado es estático y sin estado para
 * poder testearlo sin red ni LLM.
 *
 * <p>La ingesta desde Git ({@link GitHubMarkdownSource}) va aparte ({@link #ingestGitRepo}): el repo se
 * decide en cada llamada, no recorriendo una lista configurada de fuentes.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final WeaviateGateway gateway;
    private final IngestionProperties props;
    private final List<FuenteDocumental> fuentes;
    private final GitHubMarkdownSource gitHubSource;

    public IngestionService(WeaviateGateway gateway, IngestionProperties props, List<FuenteDocumental> fuentes,
                             GitHubMarkdownSource gitHubSource) {
        this.gateway = gateway;
        this.props = props;
        this.fuentes = fuentes;
        this.gitHubSource = gitHubSource;
    }

    /** Asegura el esquema y reindexa el corpus completo desde cero. Devuelve el nº de chunks insertados. */
    public int reindex() {
        gateway.dropSchema();
        gateway.ensureSchema();
        return ingestAll();
    }

    /** Asegura el esquema e ingesta el corpus (sin borrar lo existente). Devuelve el nº de chunks insertados. */
    public int ingestAll() {
        gateway.ensureSchema();
        List<Chunk> chunks = trocearTodo();
        if (chunks.isEmpty()) {
            log.warn("Ninguna fuente aportó documentos para indexar");
            return 0;
        }
        int n = gateway.ingest(chunks);
        log.info("Ingesta completada: {} chunks desde {} fuentes", n, fuentes.size());
        return n;
    }

    private List<Chunk> trocearTodo() {
        List<Chunk> chunks = new ArrayList<>();
        for (FuenteDocumental fuente : fuentes) {
            List<Documento> docs = fuente.leer();
            for (Documento doc : docs) {
                chunks.addAll(Chunker.trocear(doc, props.maxChars(), props.overlapChars()));
            }
            log.info("Fuente '{}': {} documentos troceados", fuente.nombre(), docs.size());
        }
        return chunks;
    }

    /**
     * Ingesta un repo Git puntual (repo/rama decididos en la llamada, no en config). Se acumula sobre lo
     * existente en Weaviate; para partir de cero usar antes {@link #dropVectorStore()}.
     */
    public IngestResultado ingestGitRepo(GitHubMarkdownSource.GitRepo repoEntrada) {
        GitHubMarkdownSource.GitRepo repo = gitHubSource.resolver(repoEntrada);
        List<Documento> docs = gitHubSource.leer(repo);
        List<Chunk> chunks = new ArrayList<>();
        for (Documento doc : docs) {
            chunks.addAll(Chunker.trocear(doc, props.maxChars(), props.overlapChars()));
        }
        if (chunks.isEmpty()) {
            log.warn("El repo {}@{} no aportó documentos para indexar", repo.url(), repo.branch());
            return new IngestResultado(repo.url(), repo.branch(), 0);
        }
        gateway.ensureSchema();
        int n = gateway.ingest(chunks);
        log.info("Ingesta Git completada: {} chunks desde {}@{}", n, repo.url(), repo.branch());
        return new IngestResultado(repo.url(), repo.branch(), n);
    }

    /** Vacía Weaviate sin reingestar nada (para empezar de cero antes de encadenar varias ingestas). */
    public void dropVectorStore() {
        gateway.dropSchema();
        gateway.ensureSchema();
    }

    /** Resultado de ingestar un repo Git puntual: rama resuelta (la pasada o la detectada) y chunks insertados. */
    public record IngestResultado(String url, String branch, int chunks) {}
}
