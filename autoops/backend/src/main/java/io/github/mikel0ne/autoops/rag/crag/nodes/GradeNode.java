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

public class GradeNode implements NodeAction<CragState> {

    private static final String SUFFICIENT = "SUFICIENTE";
    private static final String AMBIGUOUS = "AMBIGUO";

    public static final String COVERED = "CUBIERTA";
    public static final String PARTIAL = "PARCIAL";
    public static final String NOT_COVERED = "SIN_CUBRIR";

    private static final Pattern CHUNK_LINE = Pattern.compile("^\\s*\\[?(\\d+)]?\\s*[:.\\-]?\\s*(.*)$");

    private static final Pattern QUESTION_LINE =
            Pattern.compile("(?i)^\\s*PREGUNTA\\s*(\\d+)\\s*[:.\\-]?\\s*(.*)$");

    private final ChatModel chatModel;
    private final String promptTemplate;
    private final int maxIterations;

    public GradeNode(ChatModel chatModel, String promptTemplate, int maxIterations) {
        this.chatModel = chatModel;
        this.promptTemplate = promptTemplate;
        this.maxIterations = maxIterations;
    }

    @Override
    public Map<String, Object> apply(CragState state) {
        List<DocChunk> candidates = state.candidates();
        List<String> questions = state.questions();
        double best = candidates.stream().mapToDouble(DocChunk::score).max().orElse(0.0);

        if (candidates.isEmpty()) {
            return Map.of("bestScore", best, "sufficient", false,
                    "candidates", List.of(),
                    "questionCoverage", questions.stream().map(p -> NOT_COVERED).toList());
        }

        // Relevance is always judged by the LLM: a high hybrid/rerank score is not a reliable proxy for it.
        String answer = chatModel.chat(promptTemplate
                .replace("{{questions}}", buildQuestions(questions))
                .replace("{{context}}", buildContext(candidates)));

        Map<Integer, String> chunkVerdicts = parseChunkVerdicts(answer);
        Map<Integer, String> explicitCoverage = parseQuestionCoverage(answer);

        List<DocChunk> kept = new ArrayList<>();
        boolean hasSufficient = false;
        boolean hasAmbiguous = false;
        for (int i = 0; i < candidates.size(); i++) {
            String v = chunkVerdicts.get(i + 1);
            if (SUFFICIENT.equals(v)) {
                kept.add(candidates.get(i));
                hasSufficient = true;
            } else if (AMBIGUOUS.equals(v)) {
                kept.add(candidates.get(i));
                hasAmbiguous = true;
            }
        }
        String fallback = hasSufficient ? COVERED : hasAmbiguous ? PARTIAL : NOT_COVERED;

        List<String> coverage = new ArrayList<>(questions.size());
        boolean sufficient = true;
        for (int p = 1; p <= questions.size(); p++) {
            String questionStatus = explicitCoverage.getOrDefault(p, fallback);
            coverage.add(questionStatus);
            if (!COVERED.equals(questionStatus)) {
                sufficient = false;
            }
        }

        // Only the last round filters for good: a chunk misgraded once gets re-evaluated on the next retry.
        boolean terminal = sufficient || state.iterations() >= maxIterations;
        List<DocChunk> outputCandidates = terminal ? kept : candidates;

        return Map.of("bestScore", best, "sufficient", sufficient,
                "candidates", outputCandidates, "questionCoverage", coverage);
    }

    private static String buildQuestions(List<String> questions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < questions.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(i + 1).append(". ").append(questions.get(i));
        }
        return sb.toString();
    }

    private static String buildContext(List<DocChunk> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            DocChunk c = candidates.get(i);
            sb.append('[').append(i + 1).append("] [").append(c.source()).append(" · ").append(c.section())
                    .append("]\n").append(c.text());
        }
        return sb.toString();
    }

    private static Map<Integer, String> parseChunkVerdicts(String answer) {
        Map<Integer, String> verdicts = new LinkedHashMap<>();
        for (String line : answer.lines().toList()) {
            if (QUESTION_LINE.matcher(line).matches()) {
                continue;
            }
            Matcher m = CHUNK_LINE.matcher(line);
            if (!m.matches() || m.group(2).isBlank()) {
                continue;
            }
            int index = Integer.parseInt(m.group(1));
            String verdict = m.group(2).trim().toUpperCase();

            String type;
            // "INSUFICIENTE" contains "SUF", so the other verdicts are checked first.
            if (verdict.contains("AMBIG")) {
                type = AMBIGUOUS;
            } else if (verdict.contains("INSUF")) {
                continue;
            } else if (verdict.contains("SUF")) {
                type = SUFFICIENT;
            } else {
                continue;
            }
            verdicts.put(index, type);
        }
        return verdicts;
    }

    private static Map<Integer, String> parseQuestionCoverage(String answer) {
        Map<Integer, String> coverage = new LinkedHashMap<>();
        for (String line : answer.lines().toList()) {
            Matcher m = QUESTION_LINE.matcher(line);
            if (!m.matches() || m.group(2).isBlank()) {
                continue;
            }
            int index = Integer.parseInt(m.group(1));
            String state = m.group(2).trim().toUpperCase();
            if (state.contains("SIN")) {
                coverage.put(index, NOT_COVERED);
            } else if (state.contains("PARCIAL")) {
                coverage.put(index, PARTIAL);
            } else if (state.contains("CUBIERTA")) {
                coverage.put(index, COVERED);
            }
        }
        return coverage;
    }
}
