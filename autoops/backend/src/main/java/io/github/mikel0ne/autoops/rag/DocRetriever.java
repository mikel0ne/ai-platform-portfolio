package io.github.mikel0ne.autoops.rag;

import java.util.List;

/**
 * Recuperación de fragmentos de documentación. Abstrae el backend de búsqueda (Weaviate en producción,
 * un doble en los tests del bucle CRAG) para que los nodos del grafo no dependan de la infraestructura.
 *
 * <p>La implementación de producción ({@link WeaviateGateway}) hace <b>búsqueda híbrida</b> (vector + BM25)
 * y <b>reranking</b> nativos de Weaviate en una sola llamada (doc 03 §3).
 */
public interface DocRetriever {

    /**
     * @param consulta texto de la consulta (posiblemente reformulada por el bucle correctivo)
     * @param k        nº máximo de fragmentos a devolver
     * @return fragmentos ordenados por relevancia descendente (puede estar vacío)
     */
    List<DocChunk> retrieve(String consulta, int k);

    /**
     * Recupera todos los fragmentos de un documento, en orden. Lo usa el bucle CRAG para expandir un
     * fragmento relevante pero demasiado corto al documento completo (doc 03 §3.2).
     *
     * @param docId id estable del documento padre ({@link DocChunk#docId()})
     * @return fragmentos del documento en su orden original (vacío si no se encuentra)
     */
    List<DocChunk> documentoCompleto(String docId);
}
