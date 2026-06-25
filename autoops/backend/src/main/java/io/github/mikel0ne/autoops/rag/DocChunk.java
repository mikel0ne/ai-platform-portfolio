package io.github.mikel0ne.autoops.rag;

import java.io.Serializable;

/**
 * Fragmento de documentación recuperado del RAG. Forma canónica del contrato (doc 05 §3): es lo que
 * devuelve la tool MCP {@code buscar_docs} y lo que cita el chat de búsqueda.
 *
 * @param texto   contenido del fragmento
 * @param fuente  fichero de origen del corpus (p. ej. {@code guia-probes.md})
 * @param seccion encabezado/sección dentro del fichero
 * @param score   relevancia [0..1]: score del reranker de Weaviate si está disponible, si no el de la
 *                búsqueda híbrida
 */
public record DocChunk(String texto, String fuente, String seccion, double score) implements Serializable {}
