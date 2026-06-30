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
 * información útil (doc 03 §3.2) y, en un segundo paso de la misma llamada, si cada una de las
 * {@link CragState#preguntas()} (la nº 1 es siempre la original; el resto, si las hay, el abanico de
 * {@code multiQuery}) queda cubierta por el conjunto.
 *
 * <p>Cada fragmento recibe uno de tres veredictos: {@code SUFICIENTE} (responde por sí solo), {@code AMBIGUO}
 * (toca el tema, sin resolverlo del todo) o {@code INSUFICIENTE} (sin relación). Deliberadamente NO se usa el
 * score crudo del reranker/híbrido como atajo: ese score puede ser alto con un fragmento que no viene a
 * cuento, así que el juicio de relevancia lo hace siempre el LLM.
 *
 * <p>La cobertura por pregunta NO se infiere fragmento a fragmento (un fragmento puede responder a varias
 * preguntas de la lista si son la misma cosa dicha de otra forma — el caso típico cuando {@code multiQuery}
 * genera paráfrasis en vez de aspectos distintos); se le pide directamente al LLM, que ve el cuadro completo.
 * Si para alguna pregunta el LLM no da veredicto de cobertura (formato roto, o prompts antiguos sin este
 * paso), se infiere de los veredictos por fragmento ya parseados: CUBIERTA si hay algún SUFICIENTE, PARCIAL
 * si solo hay AMBIGUO, SIN_CUBRIR si no hay nada — el mismo criterio que regía antes de tener preguntas.
 *
 * <p>{@code suficiente} (la señal que lee el edge para parar el bucle) exige que <b>todas</b> las preguntas
 * estén {@code CUBIERTA}: una pregunta compuesta no se da por resuelta con que se responda a una sola parte.
 *
 * <p>Mientras el bucle puede seguir reintentando (no es la última vuelta), el lote completo se conserva sin
 * filtrar en {@code candidatos}: un fragmento mal calificado de INSUFICIENTE en una vuelta vuelve a evaluarse
 * en la siguiente, por si el veredicto fue un error puntual del LLM. Solo en la <b>última</b> vuelta (éxito o
 * tope de iteraciones) se filtra de forma definitiva a SUFICIENTE∪AMBIGUO, descartando lo INSUFICIENTE: eso
 * es lo que llega al redactor ({@code AnswerComposer}).
 */
public class GradeNode implements NodeAction<CragState> {

    private static final String SUFICIENTE = "SUFICIENTE";
    private static final String AMBIGUO = "AMBIGUO";

    public static final String CUBIERTA = "CUBIERTA";
    public static final String PARCIAL = "PARCIAL";
    public static final String SIN_CUBRIR = "SIN_CUBRIR";

    /** Línea esperada: "[1] SUFICIENTE", "1: INSUFICIENTE"... el índice siempre va primero. */
    private static final Pattern LINEA_FRAGMENTO = Pattern.compile("^\\s*\\[?(\\d+)]?\\s*[:.\\-]?\\s*(.*)$");
    /** Línea esperada: "PREGUNTA 1: CUBIERTA", "Pregunta 2 - SIN_CUBRIR"... */
    private static final Pattern LINEA_PREGUNTA =
            Pattern.compile("(?i)^\\s*PREGUNTA\\s*(\\d+)\\s*[:.\\-]?\\s*(.*)$");

    private final ChatModel chatModel;
    private final String promptTemplate;
    private final int maxIteraciones;

    public GradeNode(ChatModel chatModel, String promptTemplate, int maxIteraciones) {
        this.chatModel = chatModel;
        this.promptTemplate = promptTemplate;
        this.maxIteraciones = maxIteraciones;
    }

    @Override
    public Map<String, Object> apply(CragState state) {
        List<DocChunk> candidatos = state.candidatos();
        List<String> preguntas = state.preguntas();
        double mejor = candidatos.stream().mapToDouble(DocChunk::score).max().orElse(0.0);

        if (candidatos.isEmpty()) {
            return Map.of("mejorScore", mejor, "suficiente", false,
                    "candidatos", List.of(),
                    "coberturaPreguntas", preguntas.stream().map(p -> SIN_CUBRIR).toList());
        }

        String respuesta = chatModel.chat(promptTemplate
                .replace("{{preguntas}}", construirPreguntas(preguntas))
                .replace("{{contexto}}", construirContexto(candidatos)));

        Map<Integer, String> veredictosFragmento = parseFragmentos(respuesta);
        Map<Integer, String> coberturaExplicita = parsePreguntas(respuesta);

        List<DocChunk> conservados = new ArrayList<>();
        boolean haySuficiente = false;
        boolean hayAmbiguo = false;
        for (int i = 0; i < candidatos.size(); i++) {
            String v = veredictosFragmento.get(i + 1);
            if (SUFICIENTE.equals(v)) {
                conservados.add(candidatos.get(i));
                haySuficiente = true;
            } else if (AMBIGUO.equals(v)) {
                conservados.add(candidatos.get(i));
                hayAmbiguo = true;
            }
        }
        String fallback = haySuficiente ? CUBIERTA : hayAmbiguo ? PARCIAL : SIN_CUBRIR;

        List<String> cobertura = new ArrayList<>(preguntas.size());
        boolean suficiente = true;
        for (int p = 1; p <= preguntas.size(); p++) {
            String estado = coberturaExplicita.getOrDefault(p, fallback);
            cobertura.add(estado);
            if (!CUBIERTA.equals(estado)) {
                suficiente = false;
            }
        }

        boolean terminal = suficiente || state.iteraciones() >= maxIteraciones;
        List<DocChunk> candidatosSalida = terminal ? conservados : candidatos;

        return Map.of("mejorScore", mejor, "suficiente", suficiente,
                "candidatos", candidatosSalida, "coberturaPreguntas", cobertura);
    }

    private static String construirPreguntas(List<String> preguntas) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < preguntas.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(i + 1).append(". ").append(preguntas.get(i));
        }
        return sb.toString();
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

    /** Ante una línea de fragmento que no encaja con el formato pedido, se ignora: ese índice queda sin
     * veredicto y por tanto se trata como insuficiente (ver el filtro en {@link #apply}). */
    private static Map<Integer, String> parseFragmentos(String respuesta) {
        Map<Integer, String> veredictos = new LinkedHashMap<>();
        for (String linea : respuesta.lines().toList()) {
            if (LINEA_PREGUNTA.matcher(linea).matches()) {
                continue; // las líneas "PREGUNTA n: ..." las procesa parsePreguntas
            }
            Matcher m = LINEA_FRAGMENTO.matcher(linea);
            if (!m.matches() || m.group(2).isBlank()) {
                continue;
            }
            int indice = Integer.parseInt(m.group(1));
            String veredicto = m.group(2).trim().toUpperCase();
            // "INSUFICIENTE" contiene "SUF", así que se descartan primero los otros casos.
            String tipo;
            if (veredicto.contains("AMBIG")) {
                tipo = AMBIGUO;
            } else if (veredicto.contains("INSUF")) {
                continue; // insuficiente: sin entrada, se trata igual que un veredicto ausente
            } else if (veredicto.contains("SUF")) {
                tipo = SUFICIENTE;
            } else {
                continue; // irreconocible: se trata como insuficiente
            }
            veredictos.put(indice, tipo);
        }
        return veredictos;
    }

    /** Ante una línea de pregunta que no encaja o un estado irreconocible, esa pregunta queda sin entrada y
     * cae al fallback inferido de los veredictos por fragmento (ver {@link #apply}). */
    private static Map<Integer, String> parsePreguntas(String respuesta) {
        Map<Integer, String> cobertura = new LinkedHashMap<>();
        for (String linea : respuesta.lines().toList()) {
            Matcher m = LINEA_PREGUNTA.matcher(linea);
            if (!m.matches() || m.group(2).isBlank()) {
                continue;
            }
            int indice = Integer.parseInt(m.group(1));
            String estado = m.group(2).trim().toUpperCase();
            if (estado.contains("SIN")) {
                cobertura.put(indice, SIN_CUBRIR);
            } else if (estado.contains("PARCIAL")) {
                cobertura.put(indice, PARCIAL);
            } else if (estado.contains("CUBIERTA")) {
                cobertura.put(indice, CUBIERTA);
            }
            // estado irreconocible: sin entrada, cae al fallback en apply()
        }
        return cobertura;
    }
}
