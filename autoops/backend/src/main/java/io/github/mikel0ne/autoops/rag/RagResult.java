package io.github.mikel0ne.autoops.rag;

import java.util.List;

/**
 * Resultado del núcleo RAG correctivo (CRAG). Lo consumen los dos fronts: la tool MCP {@code buscar_docs}
 * (lo serializa tal cual) y el chat de búsqueda (lo redacta con el LLM interno).
 *
 * @param chunks               fragmentos catalogados como útiles (suficientes o ambiguos), ya ordenados
 * @param iteraciones          vueltas del bucle correctivo consumidas (0 = recuperación buena a la primera)
 * @param confianza            {@code alta} (todas las preguntas cubiertas), {@code media} (algunas sí, otras
 *                             no), {@code baja} (ninguna cubierta del todo, pero hay indicios ambiguos) o
 *                             {@code nula} (nada catalogado como útil)
 * @param candidatosDescartados solo en confianza {@code nula}: los mejores candidatos por score aunque el
 *                             LLM los evaluó como no relevantes — último recurso para que el redactor lo
 *                             intente igualmente, dejando claro que no se ha catalogado como bueno
 */
public record RagResult(List<DocChunk> chunks, int iteraciones, String confianza,
                        List<DocChunk> candidatosDescartados) {

    public static final String ALTA = "alta";
    public static final String MEDIA = "media";
    public static final String BAJA = "baja";
    public static final String NULA = "nula";

    /** Resultado vacío (sin candidatos en absoluto): confianza nula, sin nada que ofrecer ni de respaldo. */
    public static RagResult empty(int iteraciones) {
        return new RagResult(List.of(), iteraciones, NULA, List.of());
    }
}
