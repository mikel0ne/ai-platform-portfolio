package io.github.mikel0ne.autoops.rag.crag.nodes;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Nodo {@code grade}: <b>evalúa con el LLM interno</b> si los fragmentos recuperados bastan para responder
 * la consulta (doc 03 §3.2). Es el punto del grafo que decide si la recuperación es "buena"; su veredicto
 * dispara (o no) el bucle correctivo {@code grade → rewrite → retrieve}.
 *
 * <p>Deliberadamente NO se usa el score crudo del reranker/híbrido como atajo: en producción se observó que
 * ese score puede ser alto con un fragmento que no viene a cuento (y bajo con uno que sí), así que no es un
 * proxy fiable de relevancia — el juicio de si los fragmentos responden a la pregunta lo hace el LLM en
 * todos los casos. Ante una respuesta ambigua del LLM, se asume que NO bastan (prudente: mejor reformular y
 * reintentar que devolver una respuesta sin fundamento).
 *
 * <p>Deja en el estado {@code mejorScore} (solo para logs/depuración, no decide nada) y {@code suficiente}
 * (la decisión que lee el edge del grafo).
 */
public class GradeNode implements NodeAction<CragState> {

    private final ChatModel chatModel;
    private final String promptTemplate;

    public GradeNode(ChatModel chatModel, String promptTemplate) {
        this.chatModel = chatModel;
        this.promptTemplate = promptTemplate;
    }

    @Override
    public Map<String, Object> apply(CragState state) {
        List<DocChunk> candidatos = state.candidatos();
        double mejor = candidatos.stream().mapToDouble(DocChunk::score).max().orElse(0.0);
        boolean suficiente = !candidatos.isEmpty() && evaluarConLlm(state.consulta(), candidatos);
        return Map.of("mejorScore", mejor, "suficiente", suficiente);
    }

    /** Pregunta al LLM si los fragmentos bastan para responder. Ante respuesta ambigua, prudente: no bastan. */
    private boolean evaluarConLlm(String consulta, List<DocChunk> candidatos) {
        String contexto = candidatos.stream()
                .map(c -> "[" + c.fuente() + " · " + c.seccion() + "]\n" + c.texto())
                .collect(Collectors.joining("\n\n"));
        String prompt = promptTemplate
                .replace("{{consulta}}", consulta)
                .replace("{{contexto}}", contexto);
        String veredicto = chatModel.chat(prompt).trim().toUpperCase();
        // "INSUFICIENTE" contiene "SUF", así que se descarta primero el caso negativo.
        if (veredicto.contains("INSUF")) {
            return false;
        }
        return veredicto.contains("SUF");
    }
}
