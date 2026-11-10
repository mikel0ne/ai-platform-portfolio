package io.github.mikel0ne.autoops.rag.crag.nodes;

import dev.langchain4j.service.output.OutputParsingException;
import io.github.mikel0ne.autoops.rag.DocChunk;
import io.github.mikel0ne.autoops.rag.crag.ChunkVerdict;
import io.github.mikel0ne.autoops.rag.crag.Coverage;
import io.github.mikel0ne.autoops.rag.crag.CragState;
import io.github.mikel0ne.autoops.rag.crag.llm.GradeResult;
import io.github.mikel0ne.autoops.rag.crag.llm.GradeResult.ChunkGrade;
import io.github.mikel0ne.autoops.rag.crag.llm.GradeResult.QuestionGrade;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GradeNodeTest {

    private static final DocChunk A = chunk("A");
    private static final DocChunk B = chunk("B");

    private static DocChunk chunk(String text) {
        return new DocChunk(text, "Title", "doc.md", "Section " + text, "", "test", 0.5, "", 0, 0);
    }

    private static CragState state(int iterations, List<String> questions) {
        Map<String, Object> data = new HashMap<>();
        data.put("query", questions.get(0));
        data.put("questions", questions);
        data.put("candidates", List.of(A, B));
        data.put("iterations", iterations);
        return new CragState(data);
    }

    @Test
    void keeps_sufficient_and_ambiguous_chunks_on_the_terminal_round() {
        var node = new GradeNode((q, c) -> new GradeResult(
                List.of(new ChunkGrade(1, ChunkVerdict.SUFFICIENT), new ChunkGrade(2, ChunkVerdict.INSUFFICIENT)),
                List.of()), 0);

        Map<String, Object> out = node.apply(state(0, List.of("q")));

        assertThat(out).containsEntry("sufficient", true);
        assertThat(out.get("candidates")).isEqualTo(List.of(A));
        assertThat(out.get("questionCoverage")).isEqualTo(List.of(Coverage.COVERED));
    }

    @Test
    void explicit_question_coverage_wins_over_the_chunk_based_fallback() {
        var node = new GradeNode((q, c) -> new GradeResult(
                List.of(new ChunkGrade(1, ChunkVerdict.SUFFICIENT)),
                List.of(new QuestionGrade(1, Coverage.COVERED), new QuestionGrade(2, Coverage.NOT_COVERED))), 2);

        Map<String, Object> out = node.apply(state(0, List.of("q", "variant")));

        assertThat(out).containsEntry("sufficient", false);
        assertThat(out.get("questionCoverage")).isEqualTo(List.of(Coverage.COVERED, Coverage.NOT_COVERED));
        assertThat(out.get("candidates")).isEqualTo(List.of(A, B));
    }

    @Test
    void chunks_missing_from_the_grading_count_as_insufficient() {
        var node = new GradeNode((q, c) -> new GradeResult(null, null), 0);

        Map<String, Object> out = node.apply(state(0, List.of("q")));

        assertThat(out).containsEntry("sufficient", false);
        assertThat(out.get("candidates")).isEqualTo(List.of());
        assertThat(out.get("questionCoverage")).isEqualTo(List.of(Coverage.NOT_COVERED));
    }

    @Test
    void unparseable_grading_does_not_fail_the_search() {
        var node = new GradeNode((q, c) -> {
            throw new OutputParsingException("not json", new IllegalArgumentException("unexpected token"));
        }, 0);

        Map<String, Object> out = node.apply(state(0, List.of("q")));

        assertThat(out).containsEntry("sufficient", false);
        assertThat(out.get("questionCoverage")).isEqualTo(List.of(Coverage.NOT_COVERED));
    }
}
