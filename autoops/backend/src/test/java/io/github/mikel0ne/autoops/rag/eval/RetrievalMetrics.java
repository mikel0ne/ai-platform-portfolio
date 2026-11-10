package io.github.mikel0ne.autoops.rag.eval;

import io.github.mikel0ne.autoops.rag.DocChunk;

import java.util.List;
import java.util.Locale;

final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    record GoldenCase(String query, String source, List<String> sections) {

        boolean matches(DocChunk chunk) {
            String section = chunk.section() == null ? "" : chunk.section().toLowerCase(Locale.ROOT);
            return chunk.source() != null && chunk.source().endsWith(source)
                    && sections.stream().anyMatch(s -> section.contains(s.toLowerCase(Locale.ROOT)));
        }
    }

    // 1-based position of the first relevant chunk, 0 when none was retrieved.
    static int rank(GoldenCase golden, List<DocChunk> retrieved) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (golden.matches(retrieved.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    static double hitRate(List<Integer> ranks, int k) {
        return ranks.isEmpty() ? 0 : ranks.stream().filter(r -> r > 0 && r <= k).count() / (double) ranks.size();
    }

    static double meanReciprocalRank(List<Integer> ranks) {
        return ranks.stream().mapToDouble(r -> r == 0 ? 0 : 1.0 / r).average().orElse(0);
    }
}
