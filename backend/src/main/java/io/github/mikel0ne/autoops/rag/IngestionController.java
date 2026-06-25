package io.github.mikel0ne.autoops.rag;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Disparador de la ingesta del corpus (doc 03 §F1: "comando/endpoint de ingesta"). Operación de
 * mantenimiento: indexa los markdown de {@code corpus/} en Weaviate.
 */
@RestController
public class IngestionController {

    private final IngestionService ingestion;

    public IngestionController(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    /**
     * Indexa el corpus. Con {@code reset=true} borra primero la clase y reindexa desde cero (necesario, p.
     * ej., al cambiar de modelo de embeddings y su dimensión — doc 06 §4).
     */
    @PostMapping("/admin/ingest")
    public Map<String, Object> ingest(@RequestParam(defaultValue = "false") boolean reset) {
        int chunks = reset ? ingestion.reindex() : ingestion.ingestAll();
        return Map.of("chunks", chunks, "reset", reset);
    }
}
