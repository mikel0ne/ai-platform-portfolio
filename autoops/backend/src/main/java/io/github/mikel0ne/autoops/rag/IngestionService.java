package io.github.mikel0ne.autoops.rag;

import io.github.mikel0ne.autoops.rag.source.Documento;
import io.github.mikel0ne.autoops.rag.source.FuenteDocumental;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Indexa el corpus en Weaviate: recorre todas las {@link FuenteDocumental} disponibles (markdown local, Git,
 * Confluence...), trocea cada documento en chunks con el {@link Chunker} (secciones + ventana de vecinos),
 * calcula sus embeddings y los inserta (doc 03 §F1, doc 05 §4). El troceado es estático y sin estado para
 * poder testearlo sin red ni LLM.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final WeaviateGateway gateway;
    private final IngestionProperties props;
    private final List<FuenteDocumental> fuentes;

    public IngestionService(WeaviateGateway gateway, IngestionProperties props, List<FuenteDocumental> fuentes) {
        this.gateway = gateway;
        this.props = props;
        this.fuentes = fuentes;
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
}
