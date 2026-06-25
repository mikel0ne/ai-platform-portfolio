package io.github.mikel0ne.autoops.rag.crag.nodes;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

/**
 * Nodo {@code rewrite}: usa el LLM interno para reformular/expandir la consulta cuando la recuperación es
 * floja (sinónimos del dominio, separar preguntas compuestas). Incrementa {@code iteraciones}: el tope lo
 * impone el edge del grafo, garantizando la terminación (doc 03 §3).
 */
public class RewriteNode implements NodeAction<CragState> {

    private final ChatModel chatModel;
    private final String promptTemplate;

    public RewriteNode(ChatModel chatModel, String promptTemplate) {
        this.chatModel = chatModel;
        this.promptTemplate = promptTemplate;
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
        return Map.of(
                "consultaActual", reformulada,
                "iteraciones", state.iteraciones() + 1);
    }
}
