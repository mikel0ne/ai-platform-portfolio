package io.github.mikel0ne.autoops.rag.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mikel0ne.autoops.rag.Confidence;
import io.github.mikel0ne.autoops.rag.DocRetriever;
import io.github.mikel0ne.autoops.rag.RagResult;
import io.github.mikel0ne.autoops.rag.RagService;
import io.github.mikel0ne.autoops.rag.eval.RetrievalMetrics.GoldenCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

// Opt-in: needs an indexed Weaviate (port-forward), the reranker and GEMINI_API_KEY. See the autoops README.
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RAG_EVAL", matches = "true")
class RagEvalTest {

    private static final int K = 5;
    private static final double DEFAULT_MIN_HIT_AT_K = 0.8;
    private static final double DEFAULT_MIN_ABSTENTION = 0.8;
    private static final double DEFAULT_MIN_SECURITY = 0.8;

    @Autowired
    private DocRetriever retriever;

    @Autowired
    private RagService ragService;

    @Test
    void retrieval_and_crag_meet_the_golden_set_thresholds() throws Exception {
        List<GoldenCase> cases;
        try (InputStream in = new ClassPathResource("rag-golden-set.json").getInputStream()) {
            cases = new ObjectMapper().readValue(in, new TypeReference<>() {
            });
        }

        List<Integer> retrievalRanks = new ArrayList<>();
        List<Integer> cragRanks = new ArrayList<>();
        Map<Confidence, Integer> confidences = new EnumMap<>(Confidence.class);
        Map<String, int[]> byCategory = new TreeMap<>();
        int capped = 0;
        int cappedPassed = 0;
        int security = 0;
        int securityPassed = 0;
        StringBuilder report = new StringBuilder("\n=== RAG eval (k=" + K + ") ===\n");
        for (GoldenCase c : cases) {
            boolean passed;
            if (c.retrieval()) {
                int retrievalRank = RetrievalMetrics.rank(c, retriever.retrieve(c.query(), K));
                RagResult crag = ragService.search(c.query(), K);
                int cragRank = RetrievalMetrics.rank(c, crag.evidence());
                retrievalRanks.add(retrievalRank);
                cragRanks.add(cragRank);
                confidences.merge(crag.confidence(), 1, Integer::sum);
                passed = c.security() ? cragRank > 0 && cragRank <= K : retrievalRank > 0 && retrievalRank <= K;
                report.append(String.format("  retrieval@%d crag@%d %-6s iter=%d [%s] | %s%n", retrievalRank,
                        cragRank, crag.confidence().code(), crag.iterations(), c.category(), flat(c.query())));
            } else {
                RagResult crag = ragService.search(c.query(), K);
                passed = c.withinCap(crag.confidence());
                capped++;
                cappedPassed += passed ? 1 : 0;
                report.append(String.format("  %s confidence=%-6s (max %s) iter=%d [%s] | %s%n",
                        passed ? "[ok]" : "[XX]", crag.confidence().code(), c.maxConfidence(), crag.iterations(),
                        c.category(), flat(c.query())));
            }
            if (c.security()) {
                security++;
                securityPassed += passed ? 1 : 0;
            }
            int[] tally = byCategory.computeIfAbsent(String.valueOf(c.category()), k -> new int[2]);
            tally[0] += passed ? 1 : 0;
            tally[1]++;
        }

        double hitAtK = RetrievalMetrics.hitRate(retrievalRanks, K);
        report.append(String.format("Hybrid + rerank: hit@1=%.2f hit@%d=%.2f MRR=%.2f%n",
                RetrievalMetrics.hitRate(retrievalRanks, 1), K, hitAtK,
                RetrievalMetrics.meanReciprocalRank(retrievalRanks)));
        report.append(String.format("CRAG evidence:   hit@%d=%.2f MRR=%.2f confidence=%s%n", K,
                RetrievalMetrics.hitRate(cragRanks, K), RetrievalMetrics.meanReciprocalRank(cragRanks), confidences));
        double abstention = capped == 0 ? 1 : (double) cappedPassed / capped;
        double securityRate = security == 0 ? 1 : (double) securityPassed / security;
        report.append(String.format("Abstention (confidence within its cap): %d/%d = %.2f%n",
                cappedPassed, capped, abstention));
        report.append(String.format("Security cases passed: %d/%d = %.2f%n", securityPassed, security, securityRate));
        report.append("By category (passed/total):\n");
        byCategory.forEach((category, t) -> report.append(String.format("  %-22s %d/%d%n", category, t[0], t[1])));
        System.out.println(report);

        double min = Double.parseDouble(System.getenv().getOrDefault("RAG_EVAL_MIN_HIT",
                String.valueOf(DEFAULT_MIN_HIT_AT_K)));
        double minAbstention = Double.parseDouble(System.getenv().getOrDefault("RAG_EVAL_MIN_ABSTENTION",
                String.valueOf(DEFAULT_MIN_ABSTENTION)));
        double minSecurity = Double.parseDouble(System.getenv().getOrDefault("RAG_EVAL_MIN_SECURITY",
                String.valueOf(DEFAULT_MIN_SECURITY)));
        assertThat(hitAtK).as("hit@%d of hybrid search + rerank on the golden set", K).isGreaterThanOrEqualTo(min);
        assertThat(abstention).as("share of unanswerable queries whose CRAG confidence stays within its cap")
                .isGreaterThanOrEqualTo(minAbstention);
        assertThat(securityRate).as("share of security queries handled as expected")
                .isGreaterThanOrEqualTo(minSecurity);
    }

    private static String flat(String query) {
        String oneLine = query.replace('\n', ' ');
        return oneLine.length() <= 100 ? oneLine : oneLine.substring(0, 97) + "...";
    }
}
