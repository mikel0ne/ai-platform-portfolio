package io.github.mikel0ne.autoops.rag.crag.nodes;

import io.github.mikel0ne.autoops.rag.DocChunk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion: combina varias listas YA RANKEADAS en una sola, usando solo la posición de cada
 * candidato en su lista (nunca el score crudo). Es necesario porque el score de cada lista viene de una
 * consulta distinta (variantes del abanico, o consulta original vs. reformulada entre iteraciones del bucle
 * correctivo) y por tanto no es comparable entre listas (doc 03 §3.2bis). Lo usan {@code MultiQueryNode}
 * (fusión del abanico inicial) y {@code RetrieveNode} (fusión del histórico acumulado con cada reintento).
 */
final class RrfFusion {

    /** Constante estándar de RRF (Cormack, Clarke & Buettcher 2009): amortigua el peso de los primeros puestos. */
    private static final int RRF_K = 60;

    private RrfFusion() {
    }

    /**
     * Fusiona {@code listas} por rango y devuelve el resultado ordenado por puntuación RRF descendente, sin
     * truncar: cada llamador decide si y dónde recorta a {@code k}.
     */
    static List<DocChunk> fusionar(List<List<DocChunk>> listas) {
        Map<String, Double> puntuacionRrf = new LinkedHashMap<>();
        Map<String, DocChunk> representantePorClave = new LinkedHashMap<>();
        for (List<DocChunk> lista : listas) {
            for (int rango = 0; rango < lista.size(); rango++) {
                DocChunk candidato = lista.get(rango);
                String clave = clave(candidato);
                puntuacionRrf.merge(clave, 1.0 / (RRF_K + rango + 1), Double::sum);
                representantePorClave.merge(clave, candidato,
                        (a, b) -> a.score() >= b.score() ? a : b);
            }
        }
        return puntuacionRrf.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entrada -> representantePorClave.get(entrada.getKey()))
                .toList();
    }

    /** Identidad de un candidato a efectos de deduplicar entre listas: no hay id, así que es por contenido. */
    private static String clave(DocChunk c) {
        return c.fuente() + "|" + c.seccion() + "|" + c.texto();
    }
}
