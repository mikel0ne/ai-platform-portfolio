package io.github.mikel0ne.autoops.rag;

import java.util.List;

public record RagResult(List<DocChunk> chunks, int iterations, Confidence confidence,
                        List<DocChunk> discardedCandidates) {

    public static RagResult empty(int iterations) {
        return new RagResult(List.of(), iterations, Confidence.NONE, List.of());
    }

    // Without graded chunks the best discarded candidates are all there is to answer from.
    public List<DocChunk> evidence() {
        return chunks.isEmpty() ? discardedCandidates : chunks;
    }
}
