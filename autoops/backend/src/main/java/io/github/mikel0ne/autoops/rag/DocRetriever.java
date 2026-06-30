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
     * Recupera todos los fragmentos de una sección, en orden. Lo usa el bucle CRAG para expandir
     * (auto-merging) un fragmento relevante a su sección completa cuando conviene (doc 03 §3.2). Es un
     * lookup filtrado por {@code docId}+{@code seccion} (índice invertido), no una búsqueda semántica.
     *
     * @param docId   id estable del documento padre ({@link DocChunk#docId()})
     * @param seccion breadcrumb de la sección ({@link DocChunk#seccion()})
     * @return núcleos de la sección en su orden original (vacío si no se encuentra)
     */
    List<DocChunk> seccionCompleta(String docId, String seccion);

    /**
     * Recupera un fragmento y sus vecinos inmediatos <b>dentro de la misma sección</b> (radio de núcleos a
     * cada lado), ordenados por {@code orden}. Lo usa {@code expand} para dar un poco de contexto a un hit
     * suelto en una sección grande sin volcar la sección entera. No cruza fronteras de sección (sin bleed).
     *
     * @param docId   id estable del documento padre
     * @param seccion breadcrumb de la sección
     * @param orden   posición del fragmento en el documento ({@link DocChunk#orden()})
     * @param radio   nº de núcleos a traer a cada lado
     * @return el fragmento y sus vecinos de la sección en orden (vacío si no se encuentra)
     */
    List<DocChunk> vecinosEnSeccion(String docId, String seccion, int orden, int radio);
}
