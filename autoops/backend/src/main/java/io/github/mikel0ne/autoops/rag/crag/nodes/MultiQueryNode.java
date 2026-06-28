package io.github.mikel0ne.autoops.rag.crag.nodes;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nodo {@code multiQuery}: sustituye al {@code retrieve} de la <b>primera vuelta unicamente</b> por un
 * abanico de variantes de la consulta, recuperadas por separado y fusionadas con Reciprocal Rank Fusion
 * (RRF) antes de pasar por {@code rerank}/{@code grade} (doc 03 par.3.2bis).
 *
 * <p>El abanico no se reabre en los reintentos: si la recuperacion inicial no basta, el bucle correctivo ya
 * existente ({@code grade -> rewrite -> retrieve}) sigue reformulando de forma secuencial y subiendo
 * {@code k} como hacia antes de este nodo. Asi el coste extra (recuperaciones/embeddings de mas) queda
 * acotado a {@code multiQueryN - 1} llamadas de mas por consulta de usuario, una sola vez, en vez de
 * multiplicarse por cada reintento.
 *
 * <p>Con {@code multiQueryN <= 1} el abanico se desactiva: una sola recuperacion con la consulta tal cual,
 * sin llamada extra al LLM (equivalente exacto del antiguo {@code retrieve} en la primera vuelta).
 */
public class MultiQueryNode implements NodeAction<CragState> {

    /** Constante estandar de RRF (Cormack, Clarke & Buettcher 2009): amortigua el peso de los primeros puestos. */
    private static final int RRF_K = 60;

    private final DocRetriever retriever;
    private final ChatModel chatModel;
    private final String promptTemplate;
    private final int n;

    public MultiQueryNode(DocRetriever retriever, ChatModel chatModel, String promptTemplate, int n) {
        this.retriever = retriever;
        this.chatModel = chatModel;
        this.promptTemplate = promptTemplate;
        this.n = n;
    }

    @Override
    public Map<String, Object> apply(CragState state) {
        String original = state.consultaActual();
        int k = state.k();
        if (n <= 1) {
            return Map.of("candidatos", retriever.retrieve(original, k));
        }

        List<String> variantes = new ArrayList<>();
        variantes.add(original);
        variantes.addAll(generarVariantes(original, n - 1));

        List<List<DocChunk>> resultadosPorVariante = variantes.stream()
                .map(v -> retriever.retrieve(v, k))
                .toList();

        return Map.of("candidatos", fusionarConRrf(resultadosPorVariante, k));
    }

    /** Pide al LLM hasta {@code cuantas} reformulaciones alternativas, una por linea. */
    private List<String> generarVariantes(String consulta, int cuantas) {
        String prompt = promptTemplate
                .replace("{{consulta}}", consulta)
                .replace("{{n}}", String.valueOf(cuantas));
        String respuesta = chatModel.chat(prompt);
        return respuesta.lines()
                .map(String::trim)
                .filter(linea -> !linea.isBlank() && !linea.equalsIgnoreCase(consulta))
                .distinct()
                .limit(cuantas)
                .toList();
    }

    /**
     * Reciprocal Rank Fusion: combina varias listas rankeadas en una sola, deduplicando candidatos
     * repetidos entre variantes (suman su contribucion) y recortando al final a {@code k}, igual que
     * habria devuelto una unica recuperacion.
     */
    private List<DocChunk> fusionarConRrf(List<List<DocChunk>> listas, int k) {
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
                .limit(k)
                .map(entrada -> representantePorClave.get(entrada.getKey()))
                .toList();
    }

    /** Identidad de un candidato a efectos de deduplicar entre variantes: no hay id, asi que es por contenido. */
    private static String clave(DocChunk c) {
        return c.fuente() + "|" + c.seccion() + "|" + c.texto();
    }
}
