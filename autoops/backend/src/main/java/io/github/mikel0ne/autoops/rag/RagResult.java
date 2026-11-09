package io.github.mikel0ne.autoops.rag;

import java.util.List;

public record RagResult(List<DocChunk> chunks, int iterations, String confidence,
                        List<DocChunk> discardedCandidates) {
    public static final String HIGH = "high";
    public static final String MEDIUM = "medium";
    public static final String LOW = "low";
    public static final String NONE = "none";

    public static RagResult empty(int iterations) {
        return new RagResult(List.of(), iterations, NONE, List.of());
    }
}
