package io.github.mikel0ne.autoops.rag.crag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Parámetros del bucle correctivo (CRAG). El tope de iteraciones vive en el edge del grafo: garantiza la
 * terminación (doc 03 §3). Si los resultados son "buenos" lo decide el nodo {@code grade} con el LLM
 * interno (deliberadamente sin atajo por score crudo: ese score no es un proxy fiable de relevancia).
 *
 * <p>El bucle correctivo amplía la búsqueda en cada iteración: reformula la consulta (nodo {@code rewrite})
 * y <b>aumenta el nº de recuperaciones</b> ({@code k} crece {@code kStep} por vuelta hasta {@code kMax}),
 * recuperando candidatos cada vez más lejanos antes de volver a evaluar.
 *
 * @param k              nº de fragmentos a recuperar/devolver en la primera vuelta
 * @param maxIteraciones reformulaciones máximas antes de devolver lo mejor obtenido
 * @param alpha          peso vector vs BM25 en la búsqueda híbrida de Weaviate (1 = solo vector, 0 = solo BM25)
 * @param kStep          incremento de {@code k} en cada iteración correctiva (amplía las recuperaciones)
 * @param kMax           tope de {@code k} (no se recupera más allá de este nº por mucho que itere)
 */
@Validated
@ConfigurationProperties("autoops.crag")
public record CragProperties(
        Integer k,
        Integer maxIteraciones,
        Double alpha,
        Integer kStep,
        Integer kMax
) {
    public CragProperties {
        if (k == null) k = 5;
        if (maxIteraciones == null) maxIteraciones = 2;
        if (alpha == null) alpha = 0.5;
        if (kStep == null) kStep = 3;
        if (kMax == null) kMax = 12;
    }
}
