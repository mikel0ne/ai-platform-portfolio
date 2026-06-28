package io.github.mikel0ne.autoops.rag.crag.nodes;

import dev.langchain4j.model.chat.ChatModel;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nodo {@code grade}: <b>evalúa con el LLM interno, fragmento por fragmento,</b> si cada candidato aporta
 * información relevante para la consulta (doc 03 §3.2), en una única llamada al LLM con todo el lote.
 *
 * <p>Deliberadamente NO se usa el score crudo del reranker/híbrido como atajo: en producción se observó que
 * ese score puede ser alto con un fragmento que no viene a cuento (y bajo con uno que sí), así que no es un
 * proxy fiable de relevancia — el juicio de si cada fragmento responde a la pregunta lo hace el LLM en todos
 * los casos. Ante un veredicto ambiguo o ausente para un fragmento, se trata ese fragmento como NO relevante
 * (prudente: mejor descartarlo que dar por buena una cita que no viene a cuento).
 *
 * <p>{@code suficiente} se deriva de los veredictos por fragmento (basta con que haya al menos uno relevante;
 * el resto del bucle ya amplía la búsqueda si la cobertura es pobre), nunca de una pregunta global aparte. Los
 * fragmentos no relevantes se filtran aquí mismo: nunca llegan al redactor ({@code AnswerComposer}). Deja en
 * el estado {@code mejorScore} (solo para logs/depuración, no decide nada) y {@code suficiente} (la decisión
 * que lee el edge del grafo).
 */
public class GradeNode implements NodeAction<CragState> {

    /** Línea esperada: "[1] SUFICIENTE", "1: INSUFICIENTE", "1. SUFICIENTE"... el índice siempre va primero. */
    private static final Pattern LINEA_VEREDICTO = Pattern.compile("^\\s*\\[?(\\d+)]?\\s*[:.\\-]?\\s*(.*)$");

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
        if (candidatos.isEmpty()) {
            return Map.of("mejorScore", mejor, "suficiente", false);
        }
        List<DocChunk> relevantes = filtrarRelevantes(state.consulta(), candidatos);
        boolean suficiente = !relevantes.isEmpty();
        if (suficiente) {
            return Map.of("mejorScore", mejor, "suficiente", true, "candidatos", relevantes);
        }
        return Map.of("mejorScore", mejor, "suficiente", false);
    }

    /** Pregunta al LLM, fragmento por fragmento, y devuelve solo los marcados relevantes. */
    private List<DocChunk> filtrarRelevantes(String consulta, List<DocChunk> candidatos) {
        String prompt = promptTemplate
                .replace("{{consulta}}", consulta)
                .replace("{{contexto}}", construirContexto(candidatos));
        Map<Integer, Boolean> veredictos = parseVeredictos(chatModel.chat(prompt));

        List<DocChunk> relevantes = new ArrayList<>();
        for (int i = 0; i < candidatos.size(); i++) {
            if (Boolean.TRUE.equals(veredictos.get(i + 1))) {
                relevantes.add(candidatos.get(i));
            }
        }
        return relevantes;
    }

    private static String construirContexto(List<DocChunk> candidatos) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidatos.size(); i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            DocChunk c = candidatos.get(i);
            sb.append('[').append(i + 1).append("] [").append(c.fuente()).append(" · ").append(c.seccion())
                    .append("]\n").append(c.texto());
        }
        return sb.toString();
    }

    /** Ante una línea que no encaja con el formato pedido, se ignora: ese índice queda sin veredicto y por
     * tanto se trata como no relevante (ver el filtro en {@link #filtrarRelevantes}). */
    private static Map<Integer, Boolean> parseVeredictos(String respuesta) {
        Map<Integer, Boolean> veredictos = new LinkedHashMap<>();
        for (String linea : respuesta.lines().toList()) {
            Matcher m = LINEA_VEREDICTO.matcher(linea);
            if (!m.matches() || m.group(2).isBlank()) {
                continue;
            }
            int indice = Integer.parseInt(m.group(1));
            String veredicto = m.group(2).trim().toUpperCase();
            // "INSUFICIENTE" contiene "SUF", así que se descarta primero el caso negativo.
            boolean relevante = !veredicto.contains("INSUF") && veredicto.contains("SUF");
            veredictos.put(indice, relevante);
        }
        return veredictos;
    }
}
