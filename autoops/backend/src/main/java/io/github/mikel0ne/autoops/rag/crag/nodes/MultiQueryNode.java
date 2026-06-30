package io.github.mikel0ne.autoops.rag.crag.nodes;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.ArrayList;
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

        List<DocChunk> fusionados = RrfFusion.fusionar(resultadosPorVariante).stream().limit(k).toList();
        return Map.of("candidatos", fusionados);
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
}
