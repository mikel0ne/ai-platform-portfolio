package io.github.mikel0ne.autoops.rag.crag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Parámetros del bucle correctivo (CRAG). El tope de iteraciones vive en el edge del grafo: garantiza la
 * terminación (doc 03 §3). El umbral es sobre el mejor score del reranker/híbrido.
 *
 * @param k             nº de fragmentos a recuperar/devolver
 * @param umbral        score mínimo para considerar la recuperación "suficiente" ({@code confianza: alta})
 * @param maxIteraciones reformulaciones máximas antes de devolver lo mejor obtenido
 * @param alpha         peso vector vs BM25 en la búsqueda híbrida de Weaviate (1 = solo vector, 0 = solo BM25)
 */
@Validated
@ConfigurationProperties("autoops.crag")
public record CragProperties(
        Integer k,
        Double umbral,
        Integer maxIteraciones,
        Double alpha
) {
    public CragProperties {
        if (k == null) k = 5;
        if (umbral == null) umbral = 0.7;
        if (maxIteraciones == null) maxIteraciones = 2;
        if (alpha == null) alpha = 0.5;
    }
}
