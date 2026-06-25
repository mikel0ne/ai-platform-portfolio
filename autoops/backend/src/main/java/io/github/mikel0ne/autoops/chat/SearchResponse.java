package io.github.mikel0ne.autoops.chat;

import io.github.mikel0ne.autoops.rag.DocChunk;

import java.util.List;

/**
 * Respuesta del chat de búsqueda (doc 05 §2): texto redactado por el LLM interno más las citas que lo
 * respaldan y la confianza heredada del RAG. La tool MCP {@code buscar_docs}, en cambio, no redacta.
 *
 * @param respuesta texto redactado (anti-alucinación: si los documentos no lo contienen, lo dice)
 * @param citas     fragmentos que respaldan la respuesta
 * @param confianza {@code alta} | {@code baja} (heredada del RAG)
 */
public record SearchResponse(String respuesta, List<DocChunk> citas, String confianza) {}
