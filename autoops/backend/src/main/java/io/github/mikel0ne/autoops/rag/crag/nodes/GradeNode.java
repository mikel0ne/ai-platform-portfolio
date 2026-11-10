package io.github.mikel0ne.autoops.rag.crag.nodes;

import dev.langchain4j.service.output.OutputParsingException;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.crag.ChunkVerdict;
import io.github.mikel0ne.autoops.rag.crag.Coverage;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import io.github.mikel0ne.autoops.rag.crag.llm.GradeResult;
import io.github.mikel0ne.autoops.rag.crag.llm.Grader;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class GradeNode implements NodeAction<CragState> {

    private static final Logger log = LoggerFactory.getLogger(GradeNode.class);

    private final Grader grader;
    private final int maxIterations;

    public GradeNode(Grader grader, int maxIterations) {
        this.grader = grader;
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
                    "questionCoverage", questions.stream().map(q -> Coverage.NOT_COVERED).toList());
        }

        // Relevance is always judged by the LLM: a high hybrid/rerank score is not a reliable proxy for it.
        GradeResult result = grade(questions, candidates);
        Map<Integer, ChunkVerdict> verdicts = new HashMap<>();
        nonNull(result.chunks()).forEach(c -> verdicts.putIfAbsent(c.index(), c.verdict()));
        Map<Integer, Coverage> explicitCoverage = new HashMap<>();
        nonNull(result.questions()).forEach(q -> explicitCoverage.putIfAbsent(q.index(), q.coverage()));

        List<DocChunk> kept = new ArrayList<>();
        boolean hasSufficient = false;
        boolean hasAmbiguous = false;
        for (int i = 0; i < candidates.size(); i++) {
            ChunkVerdict verdict = verdicts.getOrDefault(i + 1, ChunkVerdict.INSUFFICIENT);
            if (verdict == ChunkVerdict.SUFFICIENT) {
                kept.add(candidates.get(i));
                hasSufficient = true;
            } else if (verdict == ChunkVerdict.AMBIGUOUS) {
                kept.add(candidates.get(i));
                hasAmbiguous = true;
            }
        }
        Coverage fallback = hasSufficient ? Coverage.COVERED : hasAmbiguous ? Coverage.PARTIAL : Coverage.NOT_COVERED;

        List<Coverage> coverage = new ArrayList<>(questions.size());
        for (int q = 1; q <= questions.size(); q++) {
            coverage.add(Objects.requireNonNullElse(explicitCoverage.get(q), fallback));
        }
        boolean sufficient = coverage.stream().allMatch(Coverage.COVERED::equals);

        // Only the last round filters for good: a chunk misgraded once gets re-evaluated on the next retry.
        boolean terminal = sufficient || state.iterations() >= maxIterations;
        List<DocChunk> outputCandidates = terminal ? kept : candidates;

        return Map.of("bestScore", best, "sufficient", sufficient,
                "candidates", outputCandidates, "questionCoverage", coverage);
    }

    private GradeResult grade(List<String> questions, List<DocChunk> candidates) {
        try {
            return grader.grade(buildQuestions(questions), buildContext(candidates));
        } catch (OutputParsingException e) {
            log.warn("Unparseable grading, treating every chunk as insufficient: {}", e.getMessage());
            return GradeResult.UNGRADED;
        }
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
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
}
