package io.github.mikel0ne.autoops.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Ingesta del corpus al RAG. La ruta apunta a la carpeta de markdown local a indexar; {@code git} declara
 * repositorios adicionales de los que clonar documentación. En local {@code corpusPath} es relativo al
 * directorio de ejecución ({@code backend/}); en K8s, al path donde se monta/copia el corpus en la imagen.
 *
 * @param corpusPath  carpeta con los {@code *.md} locales a indexar
 * @param maxChars    tamaño objetivo del núcleo de cada chunk en caracteres (~4 por token → ~500 tokens)
 * @param overlapChars solape al subdividir un párrafo demasiado largo (continuidad de contexto)
 * @param git         repositorios Git de los que ingestar markdown (vacío por defecto)
 */
@ConfigurationProperties("autoops.ingestion")
public record IngestionProperties(String corpusPath, Integer maxChars, Integer overlapChars, List<GitRepo> git) {

    /**
     * Repositorio Git del que ingestar documentación markdown.
     *
     * @param url     URL de clonado (HTTPS)
     * @param branch  rama a clonar (p. ej. {@code main})
     * @param subdir  subdirectorio al que limitar la búsqueda de {@code *.md} ({@code null}/vacío = todo el repo)
     * @param webBase base web para construir permalinks (p. ej. {@code https://github.com/org/repo}); si falta,
     *                se deriva de {@code url} quitando el sufijo {@code .git}
     */
    public record GitRepo(String url, String branch, String subdir, String webBase) {
        public GitRepo {
            if (branch == null || branch.isBlank()) branch = "main";
        }
    }

    public IngestionProperties {
        if (corpusPath == null || corpusPath.isBlank()) corpusPath = "../corpus";
        if (maxChars == null) maxChars = 2000;
        if (overlapChars == null) overlapChars = 200;
        if (git == null) git = List.of();
    }
}
