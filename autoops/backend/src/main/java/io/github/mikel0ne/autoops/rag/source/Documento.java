package io.github.mikel0ne.autoops.rag.source;

/**
 * Documento completo (sin trocear) emitido por una {@link FuenteDocumental}. El {@link io.github.mikel0ne.autoops.rag.Chunker}
 * lo parte en chunks; los metadatos de aquí se propagan a cada chunk y, vía Weaviate, a las citas del RAG.
 *
 * @param docId    id estable del documento padre (p. ej. {@code guia-probes.md} o {@code org/repo:docs/guia.md}).
 *                 Permite borrar/reindexar un documento entero sin tocar los demás.
 * @param titulo   título legible del documento (primer {@code # H1} del markdown o, si no hay, el nombre)
 * @param fuente   nombre corto del origen (fichero, page o path) — compatibilidad con el contrato previo
 * @param origen   clase de origen: {@code markdown-local} | {@code git} | {@code wikijs}
 * @param urlBase  enlace base al documento para construir citas con ancla por sección (cadena vacía si el
 *                 origen no tiene URL navegable, p. ej. markdown puramente local)
 * @param contenido markdown completo del documento
 */
public record Documento(
        String docId,
        String titulo,
        String fuente,
        String origen,
        String urlBase,
        String contenido) {
}
