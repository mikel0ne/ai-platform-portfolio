package io.github.mikel0ne.autoops.rag.crag.nodes;

import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Nodo {@code expand}: cuando {@code grade} ya dio el visto bueno, amplía los fragmentos relevantes a su
 * <b>sección</b> cuando conviene (patrón <i>auto-merging</i>, doc 03 §3.2). Es estructural y determinista:
 * agrupa los relevantes por {@code (docId, seccion)} y decide por grupo a partir de {@code seccionChars}
 * (ya recuperado con cada fragmento), sin ninguna consulta extra para decidir.
 *
 * <ul>
 *   <li><b>Caso A</b> — ≥2 núcleos relevantes de la misma sección (co-recuperación): se fusiona la sección.</li>
 *   <li><b>Caso B</b> — un único hit pero su sección es pequeña ({@code <= UMBRAL_PEQUENA}): se fusiona.</li>
 *   <li><b>Techo</b> — sección demasiado grande ({@code > TOPE_FUSION}): no se fusiona, para no diluir.</li>
 *   <li><b>Caso C</b> — un único hit en sección grande: se devuelve el núcleo con sus vecinos ±{@code RADIO_VECINOS}
 *       acotados a la sección (un poco de contexto sin volcar la sección entera, sin bleed entre secciones).</li>
 * </ul>
 *
 * <p>Solo se consulta Weaviate ({@code seccionCompleta}) para los grupos que de verdad se fusionan. Solo
 * actúa en el camino "ok": si {@code grade} no dio el visto bueno (se agotaron las iteraciones sin nada
 * relevante), {@code candidatos} no son fragmentos confirmados y no se tocan.
 */
public class ExpandNode implements NodeAction<CragState> {

    /** Sección por debajo de este tamaño (caracteres) → un hit único se expande a ella entera (Caso B). */
    private static final int UMBRAL_PEQUENA = 3000;

    /** Ninguna sección fusionada supera esto: por encima no se fusiona, para no diluir el contexto. */
    private static final int TOPE_FUSION = 8000;

    /** Caso C: nº de núcleos a traer a cada lado del hit, acotados a la sección. 0 = solo el núcleo. */
    private static final int RADIO_VECINOS = 1;

    private final DocRetriever retriever;

    public ExpandNode(DocRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public Map<String, Object> apply(CragState state) {
        if (!state.suficiente()) {
            return Map.of();
        }
        // Agrupa los relevantes por (docId, seccion), preservando el orden de aparición.
        Map<String, List<DocChunk>> porSeccion = new LinkedHashMap<>();
        for (DocChunk c : state.candidatos()) {
            porSeccion.computeIfAbsent(clave(c), k -> new ArrayList<>()).add(c);
        }
        List<DocChunk> salida = new ArrayList<>();
        for (List<DocChunk> grupo : porSeccion.values()) {
            salida.addAll(expandirGrupo(grupo));
        }
        return Map.of("candidatos", DocChunkDedup.merge(List.of(), salida));
    }

    /** Decide por grupo usando {@code seccionChars} (sin consultar); solo consulta si va a fusionar. */
    private List<DocChunk> expandirGrupo(List<DocChunk> grupo) {
        DocChunk ref = grupo.get(0);
        boolean sinDocId = ref.docId() == null || ref.docId().isBlank();
        if (sinDocId || ref.seccionChars() > TOPE_FUSION) {
            return grupo; // no se puede expandir, o la sección es demasiado grande para fusionar
        }
        if (grupo.size() >= 2 || ref.seccionChars() <= UMBRAL_PEQUENA) {
            return List.of(fusionarSeccion(grupo)); // Caso A o Caso B
        }
        return List.of(conVecinos(ref)); // Caso C: núcleo + vecinos ±RADIO_VECINOS dentro de la sección
    }

    /** Une los núcleos de la sección en un único {@link DocChunk}, heredando los metadatos del grupo. */
    private DocChunk fusionarSeccion(List<DocChunk> grupo) {
        DocChunk ref = grupo.get(0);
        List<DocChunk> seccion = retriever.seccionCompleta(ref.docId(), ref.seccion());
        if (seccion.isEmpty()) {
            return ref; // defensa: si no se recupera la sección, deja el fragmento sin tocar
        }
        String texto = seccion.stream().map(DocChunk::texto).collect(Collectors.joining("\n\n"));
        double score = grupo.stream().mapToDouble(DocChunk::score).max().orElse(ref.score());
        return new DocChunk(texto, ref.titulo(), ref.fuente(), ref.seccion(), ref.url(), ref.origen(),
                score, ref.docId(), ref.seccionChars(), ref.orden());
    }

    /** Da un poco de contexto a un hit suelto: su núcleo + los vecinos ±RADIO_VECINOS de la misma sección. */
    private DocChunk conVecinos(DocChunk hit) {
        List<DocChunk> vecinos = retriever.vecinosEnSeccion(
                hit.docId(), hit.seccion(), hit.orden(), RADIO_VECINOS);
        if (vecinos.size() <= 1) {
            return hit; // borde de sección (sin vecinos): el núcleo tal cual
        }
        String texto = vecinos.stream().map(DocChunk::texto).collect(Collectors.joining("\n\n"));
        return new DocChunk(texto, hit.titulo(), hit.fuente(), hit.seccion(), hit.url(), hit.origen(),
                hit.score(), hit.docId(), hit.seccionChars(), hit.orden());
    }

    private static String clave(DocChunk c) {
        return c.docId() + " | " + c.seccion();
    }
}
