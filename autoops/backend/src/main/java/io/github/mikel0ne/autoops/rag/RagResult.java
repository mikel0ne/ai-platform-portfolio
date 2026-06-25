package io.github.mikel0ne.autoops.rag;

import java.util.List;

/**
 * Resultado del núcleo RAG correctivo (CRAG). Lo consumen los dos fronts: la tool MCP {@code buscar_docs}
 * (lo serializa tal cual) y el chat de búsqueda (lo redacta con el LLM interno).
 *
 * @param chunks      fragmentos relevantes, ya ordenados por relevancia
 * @param iteraciones vueltas del bucle correctivo consumidas (0 = recuperación buena a la primera)
 * @param confianza   {@code alta} si se superó el umbral de score; {@code baja} si se devuelve lo mejor
 *                    obtenido sin alcanzarlo (el cliente debe matizar)
 */
public record RagResult(List<DocChunk> chunks, int iteraciones, String confianza) {

    public static final String ALTA = "alta";
    public static final String BAJA = "baja";

    /** Resultado vacío (sin candidatos): confianza baja. */
    public static RagResult empty(int iteraciones) {
        return new RagResult(List.of(), iteraciones, BAJA);
    }
}
