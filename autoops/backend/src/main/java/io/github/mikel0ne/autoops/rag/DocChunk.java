package io.github.mikel0ne.autoops.rag;

import java.io.Serializable;

/**
 * Fragmento de documentación recuperado del RAG. Forma canónica del contrato (doc 05 §3): es lo que
 * devuelve la tool MCP {@code buscar_docs} y lo que cita el chat de búsqueda.
 *
 * @param texto   contenido del fragmento (ventana: incluye el chunk anterior y el posterior para dar contexto)
 * @param titulo  título del documento de origen
 * @param fuente  origen corto del fragmento (fichero/page/path, p. ej. {@code guia-probes.md})
 * @param seccion breadcrumb de encabezados dentro del documento
 * @param url     enlace canónico con ancla a la sección (cadena vacía si el origen no es navegable)
 * @param origen  clase de origen: {@code markdown-local} | {@code git} | {@code confluence}
 * @param score   relevancia: score del reranker de Weaviate si está disponible, si no el de la búsqueda híbrida
 */
public record DocChunk(String texto, String titulo, String fuente, String seccion, String url, String origen,
                       double score) implements Serializable {}
