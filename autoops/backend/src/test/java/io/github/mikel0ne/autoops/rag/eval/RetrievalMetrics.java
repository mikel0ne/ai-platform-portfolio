package io.github.mikel0ne.autoops.rag.eval;

import io.github.mikel0ne.autoops.rag.Confidence;
import io.github.mikel0ne.autoops.rag.DocChunk;

import java.util.List;
import java.util.Locale;
import java.util.Set;

final class RetrievalMetrics {

    static final Set<String> FUNCTIONAL_CATEGORIES = Set.of("baseline", "paraphrase", "typo", "language",
            "multi-hop", "ambiguous", "hidden-section", "out-of-domain", "not-in-corpus");

    static final Set<String> SECURITY_CATEGORIES = Set.of("prompt-leak", "exfiltration", "destructive-command",
            "camouflaged-injection", "malicious-format", "grader-injection");

    private RetrievalMetrics() {
    }

    // A case either expects evidence from a document section (source + sections) or, when the corpus cannot
    // answer it, caps the CRAG confidence (maxConfidence).
    record GoldenCase(String query, String source, List<String> sections, String category, String maxConfidence) {

        GoldenCase(String query, String source, List<String> sections) {
            this(query, source, sections, null, null);
        }

        boolean retrieval() {
            return source != null;
        }

        boolean security() {
            return SECURITY_CATEGORIES.contains(category);
        }

        boolean matches(DocChunk chunk) {
            String section = chunk.section() == null ? "" : chunk.section().toLowerCase(Locale.ROOT);
            return chunk.source() != null && chunk.source().endsWith(source)
                    && sections.stream().anyMatch(s -> section.contains(s.toLowerCase(Locale.ROOT)));
        }

        Confidence confidenceCap() {
            return Confidence.valueOf(maxConfidence.toUpperCase(Locale.ROOT));
        }

        // Confidence is declared from HIGH to NONE, so a higher ordinal is a more cautious answer.
        boolean withinCap(Confidence got) {
            return got.ordinal() >= confidenceCap().ordinal();
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
