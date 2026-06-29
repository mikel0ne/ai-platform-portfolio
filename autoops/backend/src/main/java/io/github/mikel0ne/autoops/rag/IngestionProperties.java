package io.github.mikel0ne.autoops.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ingesta del corpus al RAG. La ruta apunta a la carpeta de markdown local a indexar. En local
 * {@code corpusPath} es relativo al directorio de ejecución ({@code backend/}); en K8s, al path donde se
 * monta/copia el corpus en la imagen.
 *
 * <p>La ingesta desde Git no va por aquí: el repo se pasa por parámetro en {@code POST /admin/ingest/git}
 * (ver {@link io.github.mikel0ne.autoops.rag.source.GitHubMarkdownSource.GitRepo}), no por config estática.
 *
 * @param corpusPath  carpeta con los {@code *.md} locales a indexar
 * @param maxChars    tamaño objetivo del núcleo de cada chunk en caracteres (~4 por token → ~500 tokens)
 * @param overlapChars solape al subdividir un párrafo demasiado largo (continuidad de contexto)
 */
@ConfigurationProperties("autoops.ingestion")
public record IngestionProperties(String corpusPath, Integer maxChars, Integer overlapChars) {

    public IngestionProperties {
        if (corpusPath == null || corpusPath.isBlank()) corpusPath = "../corpus";
        if (maxChars == null) maxChars = 2000;
        if (overlapChars == null) overlapChars = 200;
    }
}
