package io.github.mikel0ne.autoops.rag.crag.nodes;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

/**
 * Nodo {@code rewrite}: paso correctivo cuando la recuperación es floja. <b>Amplía la búsqueda</b> en dos
 * ejes a la vez antes de reintentar (doc 03 §3):
 * <ul>
 *   <li>reformula/expande la consulta con el LLM interno (sinónimos del dominio, separar preguntas
 *       compuestas), para pescar documentos que la formulación original no alcanzaba;</li>
 *   <li>aumenta el nº de recuperaciones {@code k} en {@code kStep} (hasta {@code kMax}): la siguiente
 *       vuelta de {@code retrieve} mira más lejos en el ranking, recuperando candidatos más distantes.</li>
 * </ul>
 * Incrementa {@code iteraciones}: el tope lo impone el edge del grafo, garantizando la terminación.
 */
public class RewriteNode implements NodeAction<CragState> {

    private final ChatModel chatModel;
    private final String promptTemplate;
    private final int kStep;
    private final int kMax;

    public RewriteNode(ChatModel chatModel, String promptTemplate, int kStep, int kMax) {
        this.chatModel = chatModel;
        this.promptTemplate = promptTemplate;
        this.kStep = kStep;
        this.kMax = kMax;
    }

    @Override
    public Map<String, Object> apply(CragState state) {
        String prompt = promptTemplate
                .replace("{{consulta_original}}", state.consulta())
                .replace("{{consulta_actual}}", state.consultaActual());
        String reformulada = chatModel.chat(prompt).trim();
        if (reformulada.isBlank()) {
            reformulada = state.consultaActual();
        }
        int kAmpliado = Math.min(state.k() + kStep, kMax);
        return Map.of(
                "consultaActual", reformulada,
                "k", kAmpliado,
                "iteraciones", state.iteraciones() + 1);
    }
}
