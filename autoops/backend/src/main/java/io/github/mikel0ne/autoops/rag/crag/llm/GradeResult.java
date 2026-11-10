package io.github.mikel0ne.autoops.rag.crag.llm;

import io.github.mikel0ne.autoops.rag.crag.ChunkVerdict;
import io.github.mikel0ne.autoops.rag.crag.Coverage;

import java.util.List;

public record GradeResult(List<ChunkGrade> chunks, List<QuestionGrade> questions) {

    public static final GradeResult UNGRADED = new GradeResult(List.of(), List.of());

    public record ChunkGrade(int index, ChunkVerdict verdict) {
    }

    public record QuestionGrade(int index, Coverage coverage) {
    }
}
