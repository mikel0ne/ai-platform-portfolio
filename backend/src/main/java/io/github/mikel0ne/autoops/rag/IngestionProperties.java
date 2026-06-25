package io.github.mikel0ne.autoops.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ingesta del corpus al RAG. La ruta apunta a la carpeta de markdown a indexar; en local es relativa al
 * directorio de ejecución ({@code backend/}), en K8s al path donde se monta/copia el corpus en la imagen.
 *
 * @param corpusPath  carpeta con los {@code *.md} a indexar
 * @param maxChars    tamaño objetivo de cada chunk en caracteres (~4 por token → ~500 tokens)
 * @param overlapChars solape entre chunks consecutivos (continuidad de contexto)
 */
@ConfigurationProperties("autoops.ingestion")
public record IngestionProperties(String corpusPath, Integer maxChars, Integer overlapChars) {
    public IngestionProperties {
        if (corpusPath == null || corpusPath.isBlank()) corpusPath = "../corpus";
        if (maxChars == null) maxChars = 2000;
        if (overlapChars == null) overlapChars = 200;
    }
}
